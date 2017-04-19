/*
 * Copyright 2017 Vector Creations Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.fragments;

import android.content.DialogInterface;
import android.os.Bundle;
import android.support.annotation.CallSuper;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.data.Room;
import org.matrix.androidsdk.data.RoomTag;
import org.matrix.androidsdk.rest.callback.ApiCallback;
import org.matrix.androidsdk.rest.model.MatrixError;
import org.matrix.androidsdk.util.BingRulesManager;
import org.matrix.androidsdk.util.Log;

import java.util.Set;

import butterknife.ButterKnife;
import butterknife.Unbinder;
import im.vector.Matrix;
import im.vector.R;
import im.vector.activity.VectorHomeActivity;
import im.vector.adapters.AbsAdapter;
import im.vector.util.RoomUtils;

/**
 * Abstract fragment providing the universal search
 */
public abstract class AbsHomeFragment extends Fragment implements AbsAdapter.InvitationListener, AbsAdapter.MoreRoomActionListener, RoomUtils.MoreActionListener {

    private static final String LOG_TAG = AbsHomeFragment.class.getSimpleName();
    private static final String CURRENT_FILTER = "CURRENT_FILTER";

    // Butterknife unbinder
    private Unbinder mUnBinder;

    protected VectorHomeActivity mActivity;

    protected String mCurrentFilter;

    protected MXSession mSession;

    protected OnRoomChangedListener mOnRoomChangedListener;

    protected final RecyclerView.OnScrollListener mScrollListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
            // warn only if there is dy i.e the list has been really scrolled not refreshed
            if ((null != mActivity) && (0 != dy)) {
                mActivity.hideFloatingActionButton(AbsHomeFragment.this.getTag());
            }
        }
    };

    /*
     * *********************************************************************************************
     * Fragment lifecycle
     * *********************************************************************************************
     */

    @Override
    @CallSuper
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    @CallSuper
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mUnBinder = ButterKnife.bind(this, view);
    }

    @Override
    @CallSuper
    public void onActivityCreated(final Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        mActivity = (VectorHomeActivity) getActivity();
        mSession = Matrix.getInstance(getActivity()).getDefaultSession();

        if (savedInstanceState != null && savedInstanceState.containsKey(CURRENT_FILTER)) {
            mCurrentFilter = savedInstanceState.getString(CURRENT_FILTER);
        }

        View fab = getActivity().findViewById(R.id.floating_action_button);
        if (fab != null) {
            fab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onFloatingButtonClick();
                }
            });
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.ic_action_mark_all_as_read:
                Log.e(LOG_TAG, "onOptionsItemSelected mark all as read");
                onMarkAllAsRead();
                return true;
        }
        return false;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(CURRENT_FILTER, mCurrentFilter);
    }

    @Override
    @CallSuper
    public void onDestroyView() {
        super.onDestroyView();
        mUnBinder.unbind();

        mCurrentFilter = null;
    }

    @Override
    @CallSuper
    public void onDetach() {
        super.onDetach();
        mActivity = null;
    }

    /*
     * *********************************************************************************************
     * Listeners
     * *********************************************************************************************
     */

    @Override
    public void onPreviewRoom(MXSession session, String roomId) {
        Log.i(LOG_TAG, "onPreviewRoom " + roomId);
        mActivity.onPreviewRoom(session, roomId);
    }

    @Override
    public void onRejectInvitation(MXSession session, String roomId) {
        Log.i(LOG_TAG, "onRejectInvitation " + roomId);
        mActivity.onRejectInvitation(session, roomId);
    }

    @Override
    public void onMoreActionClick(View itemView, Room room) {
        // User clicked on the "more actions" area
        final Set<String> tags = room.getAccountData().getKeys();
        final boolean isFavorite = tags != null && tags.contains(RoomTag.ROOM_TAG_FAVOURITE);
        final boolean isLowPriority = tags != null && tags.contains(RoomTag.ROOM_TAG_LOW_PRIORITY);
        RoomUtils.displayPopupMenu(mActivity, mSession, room, itemView, isFavorite, isLowPriority, this);
    }

    @Override
    public void onToggleRoomNotifications(MXSession session, String roomId) {
        mActivity.showWaitingView();
        RoomUtils.toggleNotifications(session, roomId, new BingRulesManager.onBingRuleUpdateListener() {
            @Override
            public void onBingRuleUpdateSuccess() {
                onRequestDone(null);
            }

            @Override
            public void onBingRuleUpdateFailure(final String errorMessage) {
                onRequestDone(errorMessage);
            }
        });
    }

    @Override
    public void onToggleDirectChat(MXSession session, final String roomId) {
        mActivity.showWaitingView();
        RoomUtils.toggleDirectChat(session, roomId, new ApiCallback<Void>() {
            @Override
            public void onSuccess(Void info) {
                onRequestDone(null);
                if (mOnRoomChangedListener != null) {
                    mOnRoomChangedListener.onToggleDirectChat(roomId, RoomUtils.isDirectChat(mSession, roomId));
                }
            }

            @Override
            public void onNetworkError(Exception e) {
                onRequestDone(e.getLocalizedMessage());
            }

            @Override
            public void onMatrixError(MatrixError e) {
                onRequestDone(e.getLocalizedMessage());
            }

            @Override
            public void onUnexpectedError(Exception e) {
                onRequestDone(e.getLocalizedMessage());
            }
        });
    }

    @Override
    public void moveToFavorites(MXSession session, String roomId) {
        updateTag(roomId, RoomTag.ROOM_TAG_FAVOURITE);
    }

    @Override
    public void moveToConversations(MXSession session, String roomId) {
        updateTag(roomId, null);
    }

    @Override
    public void moveToLowPriority(MXSession session, String roomId) {
        updateTag(roomId, RoomTag.ROOM_TAG_LOW_PRIORITY);
    }

    @Override
    public void onLeaveRoom(final MXSession session, final String roomId) {
        RoomUtils.showLeaveRoomDialog(getActivity(), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (mActivity != null && !mActivity.isFinishing()) {
                    mActivity.onRejectInvitation(session, roomId);
                    if (mOnRoomChangedListener != null) {
                        mOnRoomChangedListener.onRoomLeft(roomId);
                    }
                }
            }
        });
    }

    /*
     * *********************************************************************************************
     * Public methods
     * *********************************************************************************************
     */

    /**
     * Apply the filter
     *
     * @param pattern
     */
    public void applyFilter(final String pattern) {
        if (TextUtils.isEmpty(pattern)) {
            if (mCurrentFilter != null) {
                onResetFilter();
                mCurrentFilter = null;
            }
        } else if (!TextUtils.equals(mCurrentFilter, pattern)) {
            onFilter(pattern, new OnFilterListener() {
                @Override
                public void onFilterDone(int nbItems) {
                    mCurrentFilter = pattern;
                }
            });
        }
    }

    /**
     * A room summary has been updated
     */
    public void onSummariesUpdate() {
    }

    /*
     * *********************************************************************************************
     * Private methods
     * *********************************************************************************************
     */

    /**
     * Change the tag of the given room with the provided one
     *
     * @param roomId
     * @param newTag
     */
    private void updateTag(final String roomId, final String newTag) {
        mActivity.showWaitingView();
        RoomUtils.updateRoomTag(mSession, roomId, newTag, new ApiCallback<Void>() {
            @Override
            public void onSuccess(Void info) {
                onRequestDone(null);
            }

            @Override
            public void onNetworkError(Exception e) {
                onRequestDone(e.getLocalizedMessage());
            }

            @Override
            public void onMatrixError(MatrixError e) {
                onRequestDone(e.getLocalizedMessage());
            }

            @Override
            public void onUnexpectedError(Exception e) {
                onRequestDone(e.getLocalizedMessage());
            }
        });
    }

    /**
     * Handle the end of any request : hide loading wheel and display error message if there is any
     *
     * @param errorMessage
     */
    private void onRequestDone(final String errorMessage) {
        if (mActivity != null && !mActivity.isFinishing()) {
            mActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mActivity.stopWaitingView();
                    if (!TextUtils.isEmpty(errorMessage)) {
                        Toast.makeText(mActivity, errorMessage, Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
    }

    /*
     * *********************************************************************************************
     * Abstract methods
     * *********************************************************************************************
     */

    protected abstract void onMarkAllAsRead();

    protected abstract void onFloatingButtonClick();

    protected abstract void onFilter(final String pattern, final OnFilterListener listener);

    protected abstract void onResetFilter();

    /*
     * *********************************************************************************************
     * Listener
     * *********************************************************************************************
     */

    public interface OnFilterListener {
        void onFilterDone(final int nbItems);
    }

    public interface OnRoomChangedListener {

        void onToggleDirectChat(final String roomId, final boolean isDirectChat);

        void onRoomLeft(final String roomId);
    }
}