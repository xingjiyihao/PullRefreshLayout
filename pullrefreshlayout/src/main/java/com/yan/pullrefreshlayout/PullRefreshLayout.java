package com.yan.pullrefreshlayout;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.support.annotation.NonNull;
import android.support.v4.view.NestedScrollingChild;
import android.support.v4.view.NestedScrollingParent;
import android.support.v4.view.NestedScrollingParentHelper;
import android.support.v4.view.ViewCompat;
import android.support.v4.widget.ScrollerCompat;
import android.support.v7.widget.RecyclerView;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.AbsListView;
import android.widget.FrameLayout;

/**
 * Created by yan on 2017/4/11.
 */
public class PullRefreshLayout extends FrameLayout implements NestedScrollingParent {
    private NestedScrollingParentHelper parentHelper;

    /**
     * refresh header layout
     */
    PullFrameLayout headerViewLayout;

    /**
     * refresh footer layout
     */
    PullFrameLayout footerViewLayout;

    /**
     * refresh header
     */
    View headerView;

    /**
     * refresh footer
     */
    View footerView;

    /**
     * current refreshing state 1:refresh 2:loadMore
     */
    private int refreshState = 0;

    /**
     * last Scroll Y
     */
    private int lastScrollY = 0;

    /**
     * twink during adjust value
     */
    private int adjustTwinkDuring = 3;

    /**
     * over scroll state
     */
    private int overScrollState = 0;

    /**
     * drag move distance
     */
    volatile int moveDistance = 0;

    /**
     * refresh target view
     */
    View targetView;

    /**
     * header or footer height
     */
    private float headerHeight = 60;

    /**
     * header or footer height
     */
    private float footerHeight = 60;

    /**
     * max height drag
     */
    private float pullFlowHeight = -1;

    /**
     * the ratio for final distance for drag
     */
    private float dragDampingRatio = 0.6f;

    /**
     * move distance ratio for over scroll
     */
    private float overScrollDampingRatio = 0.2f;

    /**
     * animation during adjust value
     */
    private float duringAdjustValue = 10f;

    /**
     * animation during adjust value
     */
    private float currentVelocityY = 0;

    /**
     * switch refresh enable
     */
    private boolean pullRefreshEnable = true;

    /**
     * is Twink enable
     */
    private boolean pullTwinkEnable = true;

    /**
     * switch loadMore enable
     */
    private boolean pullLoadMoreEnable = false;

    /**
     * refreshState is isRefreshing
     */
    private boolean isRefreshing = false;

    /**
     * make sure header or footer hold trigger one time
     */
    private boolean pullStateControl = true;

    /**
     * has called the method refreshComplete or loadMoreComplete
     */
    private boolean isResetTrigger = false;

    /**
     * is able auto load more
     */
    private boolean autoLoadingEnable = false;

    /**
     * is able auto load more
     */
    private boolean autoLoadTrigger = false;

    /**
     * is over scroll trigger
     */
    private boolean isOverScrollTrigger = false;

    /**
     * refresh back time
     * if the value equals -1, the field duringAdjustValue will be work
     */
    private long refreshBackTime = 350;

    private OnRefreshListener onRefreshListener;

    private ValueAnimator currentAnimation;

    private ValueAnimator scrollAnimation;

    private ScrollerCompat scroller;

    private GeneralPullHelper generalPullHelper;

    private RefreshShowHelper refreshShowHelper;

    public PullRefreshLayout(Context context) {
        super(context);
        pullInit(context);
    }

    public PullRefreshLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        pullInit(context);
    }

    public PullRefreshLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        pullInit(context);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (getChildCount() <= 2) {
            throw new RuntimeException("PullRefreshLayout should have one child");
        }
        targetView = getChildAt(2);
        if (!(targetView instanceof NestedScrollingChild)) {
            generalPullHelper = new GeneralPullHelper(this);
        }
        initScroller();
    }

    private void pullInit(Context context) {
        refreshShowHelper = new RefreshShowHelper(this);

        parentHelper = new NestedScrollingParentHelper(this);
        headerHeight = dipToPx(context, headerHeight);
        footerHeight = dipToPx(context, footerHeight);

        headerViewLayout = new PullFrameLayout(getContext());
        footerViewLayout = new PullFrameLayout(getContext());
        LayoutParams layoutParams = new LayoutParams(LayoutParams.MATCH_PARENT
                , LayoutParams.WRAP_CONTENT);
        addView(headerViewLayout, layoutParams);
        addView(footerViewLayout, layoutParams);
        setHeaderView(headerView);
        setFooterView(footerView);
    }

    private void initScroller() {
        if (pullTwinkEnable && scroller == null) {
            if (targetView instanceof RecyclerView) {
                scroller = ScrollerCompat.create(getContext(), getRecyclerDefaultInterpolator());
                return;
            }
            scroller = ScrollerCompat.create(getContext());
        }
    }

    private Interpolator getRecyclerDefaultInterpolator() {
        return new Interpolator() {
            @Override
            public float getInterpolation(float t) {
                t -= 1.0f;
                return t * t * t * t * t + 1.0f;
            }
        };
    }

    /**
     * onOverScrollUp
     */
    private void onOverScrollUp() {
        overScrollState = 1;
    }

    /**
     * onOverScrollDown
     */
    private void onOverScrollDown() {
        overScrollState = 2;
        if (autoLoadingEnable && !isRefreshing && onRefreshListener != null && !autoLoadTrigger) {
            autoLoadTrigger = true;
            onRefreshListener.onLoading();
        }
    }

    @Override
    public void computeScroll() {
        if (scroller != null && scroller.computeScrollOffset()) {
            if (!isOverScrollTrigger && !canChildScrollUp() && canChildScrollDown() && currentVelocityY < 0) {
                isOverScrollTrigger = true;
                onOverScrollUp();
            } else if (!isOverScrollTrigger && !canChildScrollDown() && canChildScrollUp() && currentVelocityY > 0) {
                isOverScrollTrigger = true;
                onOverScrollDown();
            }

            int currY = scroller.getCurrY();
            int tempDistance = currY - lastScrollY;
            if (currentVelocityY > 0 && moveDistance >= 0 && targetView instanceof NestedScrollingChild) {
                if (moveDistance - tempDistance <= 0) {
                    onScroll(-moveDistance);
                } else if (tempDistance < 1000) {
                    onScroll(-tempDistance);
                }
            } else if (currentVelocityY < 0 && moveDistance <= 0 && targetView instanceof NestedScrollingChild) {
                if (moveDistance + tempDistance >= 0) {
                    onScroll(-moveDistance);
                } else if (tempDistance < 1000) {
                    onScroll(tempDistance);
                }
            }
            overScrollLogic(tempDistance);
            lastScrollY = currY;

            invalidate();
        }
    }

    /**
     * get Final Over Scroll Distance
     *
     * @return
     */
    private int getFinalOverScrollDistance() {
        return (int) (Math.pow((scroller.getFinalY() - scroller.getCurrY()) * adjustTwinkDuring, 0.4));
    }

    /**
     * scroll over logic
     *
     * @param tempDistance scroll distance
     */
    private void overScrollLogic(int tempDistance) {
        if (overScrollState == 1) {
            startScrollAnimation(tempDistance);
        } else if (overScrollState == 2) {
            startScrollAnimation(-tempDistance);
        }
    }

    /**
     * dell over scroll to move children
     */
    private void startScrollAnimation(final int distanceMove) {
        overScrollState = 0;
        int finalDistance = getFinalOverScrollDistance();
        cancelCurrentAnimation();

        if (scrollAnimation == null) {
            scrollAnimation = ValueAnimator.ofInt(distanceMove, 0);
            scrollAnimation.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    onScroll((Integer) animation.getAnimatedValue() * overScrollDampingRatio);
                }
            });
            scrollAnimation.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    handleAction();
                }
            });
//            scrollAnimation.setInterpolator(new DecelerateInterpolator(1f));
        } else {
            scrollAnimation.setIntValues(distanceMove, 0);
        }
        scrollAnimation.setDuration(getAnimationTime(finalDistance));

        currentAnimation = scrollAnimation;
        scrollAnimation.start();
    }

    /**
     * @return Whether it is possible for the child view of this layout to
     * scroll up. Override this if the child view is a custom view.
     */
    public boolean canChildScrollUp() {
        if (android.os.Build.VERSION.SDK_INT < 14) {
            if (targetView instanceof AbsListView) {
                final AbsListView absListView = (AbsListView) targetView;
                return absListView.getChildCount() > 0
                        && (absListView.getFirstVisiblePosition() > 0 || absListView.getChildAt(0)
                        .getTop() < absListView.getPaddingTop());
            } else {
                return ViewCompat.canScrollVertically(targetView, -1) || targetView.getScrollY() > 0;
            }
        } else {
            return ViewCompat.canScrollVertically(targetView, -1);
        }
    }

    /**
     * @return Whether it is possible for the child view of this layout to
     * scroll down. Override this if the child view is a custom view.
     */
    public boolean canChildScrollDown() {
        if (android.os.Build.VERSION.SDK_INT < 14) {
            if (targetView instanceof AbsListView) {
                final AbsListView absListView = (AbsListView) targetView;
                if (absListView.getChildCount() > 0) {
                    int lastChildBottom = absListView.getChildAt(absListView.getChildCount() - 1).getBottom();
                    return absListView.getLastVisiblePosition() == absListView.getAdapter().getCount() - 1
                            && lastChildBottom <= absListView.getMeasuredHeight();
                } else {
                    return false;
                }
            } else {
                return ViewCompat.canScrollVertically(targetView, 1) || targetView.getScrollY() > 0;
            }
        } else {
            return ViewCompat.canScrollVertically(targetView, 1);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (headerView != null) {
            headerView.measure(0, 0);
            headerHeight = headerView.getMeasuredHeight();
        }
        if (footerView != null) {
            footerViewLayout.measure(0, 0);
            footerHeight = footerView.getMeasuredHeight();
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        headerViewLayout.layout(0, 0, getMeasuredWidth(), moveDistance);
        footerViewLayout.layout(0, bottom + moveDistance, getMeasuredWidth(), bottom);
    }

    @Override
    public boolean onStartNestedScroll(View child, View target, int nestedScrollAxes) {
        isOverScrollTrigger = false;
        cancelCurrentAnimation();
        overScrollState = 0;
        return true;
    }

    @Override
    public void onNestedScrollAccepted(View child, View target, int axes) {
        parentHelper.onNestedScrollAccepted(child, target, axes);
    }

    /**
     * handler : refresh or loading
     *
     * @param child : child view of PullRefreshLayout,RecyclerView or Scroller
     */
    @Override
    public void onStopNestedScroll(View child) {
        parentHelper.onStopNestedScroll(child);
    }

    /**
     * with child view to processing move events
     *
     * @param target   the child view
     * @param dx       move x
     * @param dy       move y
     * @param consumed parent consumed move distance
     */
    @Override
    public void onNestedPreScroll(View target, int dx, int dy, int[] consumed) {
        if (Math.abs(dy) > 200) {
            return;
        }
        if (dy > 0 && moveDistance > 0) {
            if (moveDistance - dy < 0) {
                onScroll(-moveDistance);
                consumed[1] += dy;
                return;
            }
            onScroll(-dy);
            consumed[1] += dy;
        }
        if (dy < 0 && moveDistance < 0) {
            if (moveDistance - dy > 0) {
                onScroll(-moveDistance);
                consumed[1] += dy;
                return;
            }
            onScroll(-dy);
            consumed[1] += dy;
        }
    }

    @Override
    public void onNestedScroll(View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed) {
        dyUnconsumed = (int) (dyUnconsumed * dragDampingRatio);
        onScroll(-dyUnconsumed);
    }

    @Override
    public int getNestedScrollAxes() {
        return parentHelper.getNestedScrollAxes();
    }

    @Override
    public boolean onNestedPreFling(View target, float velocityX, float velocityY) {
        if (pullTwinkEnable || autoLoadingEnable) {
            currentVelocityY = velocityY;
            scroller.fling(0, 0, 0, (int) Math.abs(currentVelocityY), 0, 0, 0, Integer.MAX_VALUE);
            lastScrollY = 0;
            invalidate();
        }
        return false;
    }

    @Override
    public boolean onNestedFling(View target, float velocityX, float velocityY, boolean consumed) {
        return false;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (targetView instanceof NestedScrollingChild) {
            return !(!pullRefreshEnable && !pullLoadMoreEnable) && super.onInterceptTouchEvent(ev);
        }
        return !generalPullHelper.onInterceptTouchEvent(ev) && super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (targetView instanceof NestedScrollingChild) {
            return super.onTouchEvent(event);
        }
        return generalPullHelper.onTouchEvent(event);
    }

    private void actionEndHandleAction(MotionEvent ev) {
        if ((ev.getAction() == MotionEvent.ACTION_CANCEL
                || ev.getAction() == MotionEvent.ACTION_UP)
                && moveDistance != 0) {
            handleAction();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (!(targetView instanceof NestedScrollingChild)) {
            actionEndHandleAction(ev);
            return !generalPullHelper.dispatchTouchEvent(ev) && super.dispatchTouchEvent(ev);
        }
        actionEndHandleAction(ev);
        return super.dispatchTouchEvent(ev);
    }

    /**
     * dell the nestedScroll
     *
     * @param distanceY move distance of Y
     */
    private void onScroll(float distanceY) {
        if (pullFlowHeight != -1) {
            if (moveDistance + distanceY > pullFlowHeight) {
                moveDistance = (int) pullFlowHeight;
            } else if (moveDistance + distanceY < -pullFlowHeight) {
                moveDistance = -(int) pullFlowHeight;
            } else {
                moveDistance += distanceY;
            }
        } else {
            moveDistance += distanceY;
        }

        if (!pullTwinkEnable && isRefreshing
                && ((refreshState == 1 && moveDistance < 0)
                || (refreshState == 2 && moveDistance > 0))) {
            moveDistance = 0;
        }

        if ((pullLoadMoreEnable && moveDistance <= 0)
                || (pullRefreshEnable && moveDistance >= 0)
                || pullTwinkEnable) {
            moveChildren(moveDistance);
        } else {
            moveDistance = 0;
            return;
        }

        if (moveDistance >= 0) {
            if (headerView != null && headerView instanceof OnPullListener) {
                ((OnPullListener) headerView).onPullChange(
                        refreshShowHelper.headerOffsetRatio(moveDistance / headerHeight)
                );
            }
            if (moveDistance >= headerHeight) {
                if (pullStateControl) {
                    pullStateControl = false;
                    if (headerView != null && !isRefreshing && headerView instanceof OnPullListener) {
                        ((OnPullListener) headerView).onPullHoldTrigger();
                    }
                }
                return;
            }
            if (!pullStateControl) {
                pullStateControl = true;
                if (headerView != null && !isRefreshing && headerView instanceof OnPullListener) {
                    ((OnPullListener) headerView).onPullHoldUnTrigger();
                }
            }
            return;
        }
        if (footerView != null && footerView instanceof OnPullListener) {
            ((OnPullListener) footerView).onPullChange(
                    refreshShowHelper.footerOffsetRatio(moveDistance / headerHeight)
            );
        }
        if (moveDistance <= -footerHeight) {
            if (pullStateControl) {
                pullStateControl = false;
                if (footerView != null && !isRefreshing && footerView instanceof OnPullListener) {
                    ((OnPullListener) footerView).onPullHoldTrigger();
                }
            }
            return;
        }
        if (!pullStateControl) {
            pullStateControl = true;
            if (footerView != null && !isRefreshing && footerView instanceof OnPullListener) {
                ((OnPullListener) footerView).onPullHoldUnTrigger();
            }
        }
    }

    /**
     * move children
     */
    private void moveChildren(float distance) {
        dellAutoLoading();
        ViewCompat.setTranslationY(targetView, distance);
        dellRefreshViewCenter(distance);
    }

    private void dellAutoLoading() {
        if (moveDistance < 0) {
            if (autoLoadingEnable && !isRefreshing && onRefreshListener != null && !autoLoadTrigger) {
                autoLoadTrigger = true;
                onRefreshListener.onLoading();
            }
        }
    }

    /**
     * make sure refresh view center in parent
     *
     * @param distance
     */
    private void dellRefreshViewCenter(float distance) {
        ViewGroup.LayoutParams headerLayoutParams = headerViewLayout.getLayoutParams();
        headerLayoutParams.height = (int) distance;
        headerViewLayout.setLayoutParams(headerLayoutParams);

        ViewGroup.LayoutParams footerLayoutParams = footerViewLayout.getLayoutParams();
        footerLayoutParams.height = (int) distance;
        footerViewLayout.setLayoutParams(footerLayoutParams);
    }

    /**
     * decide on the action refresh or loadMore
     */
    private void handleAction() {
        if (pullRefreshEnable && refreshState != 2
                && !isResetTrigger && moveDistance >= headerHeight) {
            startRefresh(moveDistance, true);
        } else if ((!isRefreshing && moveDistance > 0 && refreshState != 2)
                || (isResetTrigger && refreshState == 1)
                || moveDistance > 0 && refreshState == 2) {
            resetHeaderView(moveDistance);
        }
        if (pullLoadMoreEnable && refreshState != 1
                && !isResetTrigger && moveDistance <= -footerHeight) {
            startLoadMore(moveDistance);
        } else if ((!isRefreshing && moveDistance < 0 && refreshState != 1)
                || (isResetTrigger && refreshState == 2)
                || moveDistance < 0 && refreshState == 1) {
            resetFootView(moveDistance);
        }
    }

    /**
     * start Refresh
     *
     * @param headerViewHeight
     */
    private void startRefresh(int headerViewHeight, final boolean withAction) {
        if (headerView != null && headerView instanceof OnPullListener) {
            ((OnPullListener) headerView).onPullHolding();
        }
        ValueAnimator animator = ValueAnimator.ofInt(headerViewHeight, (int) headerHeight);
        cancelCurrentAnimation();
        currentAnimation = animator;
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                moveDistance = (Integer) animation.getAnimatedValue();
                if (headerView != null && headerView instanceof OnPullListener) {
                    ((OnPullListener) headerView).onPullChange(
                            refreshShowHelper.headerOffsetRatio(moveDistance / headerHeight)
                    );
                }
                moveChildren(moveDistance);
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                refreshState = 1;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!isRefreshing) {
                    if (onRefreshListener != null && withAction) {
                        onRefreshListener.onRefresh();
                    }
                    isRefreshing = true;

                    if (footerView != null) {
                        footerView.setVisibility(GONE);
                    }
                }
            }
        });
        if (headerViewHeight == 0) {
            animator.setDuration(refreshBackTime);
        } else {
            animator.setDuration(getAnimationTime(moveDistance));
        }
        animator.setInterpolator(new DecelerateInterpolator(2f));
        animator.start();
    }

    /**
     * reset refresh refreshState
     *
     * @param headerViewHeight
     */
    private void resetHeaderView(int headerViewHeight) {
        if (headerViewHeight == 0 && refreshState == 1) {
            resetRefreshState();
            return;
        }
        ValueAnimator animator = ValueAnimator.ofInt(headerViewHeight, 0);
        cancelCurrentAnimation();
        currentAnimation = animator;
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                moveDistance = (Integer) animation.getAnimatedValue();
                if (headerView != null && headerView instanceof OnPullListener) {
                    ((OnPullListener) headerView).onPullChange(
                            refreshShowHelper.headerOffsetRatio(moveDistance / headerHeight)
                    );
                }
                moveChildren(moveDistance);
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                if (headerView != null && isRefreshing && refreshState == 1 && headerView instanceof OnPullListener) {
                    ((OnPullListener) headerView).onPullFinish();
                }
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (refreshState == 1) {
                    resetRefreshState();
                }
            }
        });
        animator.setDuration(refreshBackTime != -1 ? refreshBackTime : getAnimationTime(moveDistance));
        animator.start();
    }

    private void resetRefreshState() {
        if (headerView != null && headerView instanceof OnPullListener) {
            ((OnPullListener) headerView).onPullReset();
        }
        if (footerView != null) {
            footerView.setVisibility(VISIBLE);
        }
        isRefreshing = false;
        refreshState = 0;
        isResetTrigger = false;
        pullStateControl = true;

    }

    /**
     * start loadMore
     *
     * @param loadMoreViewHeight
     */
    private void startLoadMore(int loadMoreViewHeight) {
        if (footerView != null && footerView instanceof OnPullListener) {
            ((OnPullListener) footerView).onPullHolding();
        }
        ValueAnimator animator = ValueAnimator.ofInt(loadMoreViewHeight, -(int) footerHeight);
        cancelCurrentAnimation();
        currentAnimation = animator;
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                moveDistance = (Integer) animation.getAnimatedValue();
                if (footerView != null && footerView instanceof OnPullListener) {
                    ((OnPullListener) footerView).onPullChange(
                            refreshShowHelper.footerOffsetRatio(moveDistance / headerHeight)
                    );
                }
                moveChildren(moveDistance);
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                refreshState = 2;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (onRefreshListener != null && !isRefreshing) {
                    onRefreshListener.onLoading();
                    isRefreshing = true;

                    if (headerView != null) {
                        headerView.setVisibility(GONE);
                    }
                }
            }
        });
        animator.setDuration(getAnimationTime(moveDistance));
        animator.setInterpolator(new DecelerateInterpolator(2f));
        animator.start();
    }

    /**
     * reset loadMore refreshState
     *
     * @param loadMoreViewHeight
     */
    private void resetFootView(int loadMoreViewHeight) {
        if (loadMoreViewHeight == 0 && refreshState == 2) {
            resetLoadMoreState();
            return;
        }
        ValueAnimator animator = ValueAnimator.ofInt(loadMoreViewHeight, 0);
        cancelCurrentAnimation();
        currentAnimation = animator;
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                moveDistance = (Integer) animation.getAnimatedValue();
                if (footerView != null && footerView instanceof OnPullListener) {
                    ((OnPullListener) footerView).onPullChange(
                            refreshShowHelper.footerOffsetRatio(moveDistance / headerHeight)
                    );
                }
                moveChildren(moveDistance);
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (refreshState == 2) {
                    resetLoadMoreState();
                }
            }

            @Override
            public void onAnimationStart(Animator animation) {
                if (footerView != null && isRefreshing && refreshState == 2 && footerView instanceof OnPullListener) {
                    ((OnPullListener) footerView).onPullFinish();
                }
            }
        });
        animator.setDuration(refreshBackTime != -1 ? refreshBackTime : getAnimationTime(moveDistance));
        animator.start();
    }

    private void resetLoadMoreState() {
        if (footerView != null && footerView instanceof OnPullListener) {
            ((OnPullListener) footerView).onPullReset();
        }
        if (headerView != null) {
            headerView.setVisibility(VISIBLE);
        }
        isRefreshing = false;
        refreshState = 0;
        isResetTrigger = false;
        pullStateControl = true;
    }

    /**
     * callback on refresh finish
     */
    public void refreshComplete() {
        if (refreshState == 1) {
            isResetTrigger = true;
            resetHeaderView(moveDistance);
        }
    }

    /**
     * Callback on loadMore finish
     */
    public void loadMoreComplete() {
        if (refreshState == 2) {
            isResetTrigger = true;
            resetFootView(moveDistance);
        }
        autoLoadTrigger = false;
    }

    public void autoRefresh() {
        autoRefresh(true);
    }

    public void autoRefresh(boolean withAction) {
        if (targetView == null || !pullRefreshEnable) {
            return;
        }
        if (!(targetView instanceof NestedScrollingChild)) {
            generalPullHelper.autoRefreshDell();
        }
        startRefresh(0, withAction);
    }

    private void cancelCurrentAnimation() {
        if (currentAnimation != null && currentAnimation.isRunning()) {
            currentAnimation.cancel();
        }
        if (scroller != null && scroller.computeScrollOffset()) {
            scroller.abortAnimation();
        }
    }

    private long getAnimationTime(int moveDistance) {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        WindowManager windowManager = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        windowManager.getDefaultDisplay().getMetrics(displayMetrics);
        float ratio = Math.abs((float) moveDistance / (float) displayMetrics.heightPixels);
        return (long) (Math.pow(2000 * ratio, 0.5) * duringAdjustValue);
    }

    private float dipToPx(Context context, float value) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, metrics);
    }

    public void setHeaderView(View header) {
        if (header != null) {
            headerView = header;
            headerViewLayout.removeAllViewsInLayout();
            refreshShowHelper.dellRefreshHeaderShow();
            headerViewLayout.addView(headerView);
        }
    }

    public void setFooterView(View footer) {
        if (footer != null) {
            footerView = footer;
            footerViewLayout.removeAllViewsInLayout();
            refreshShowHelper.dellRefreshFooterShow();
            footerViewLayout.addView(footerView);
        }
    }

    public void setLoadMoreEnable(boolean mPullLoadEnable) {
        this.pullLoadMoreEnable = mPullLoadEnable;
    }

    public void setRefreshEnable(boolean mPullRefreshEnable) {
        this.pullRefreshEnable = mPullRefreshEnable;
    }

    public void setPullTwinkEnable(boolean pullTwinkEnable) {
        this.pullTwinkEnable = pullTwinkEnable;
    }

    public void setOnRefreshListener(OnRefreshListener onRefreshListener) {
        this.onRefreshListener = onRefreshListener;
    }

    public void setOverScrollDampingRatio(float overScrollDampingRatio) {
        this.overScrollDampingRatio = overScrollDampingRatio;
    }

    private void setScrollInterpolator(Interpolator interpolator) {
        scroller = ScrollerCompat.create(getContext(), interpolator);
    }

    public void setPullFlowHeight(float pullFlowHeight) {
        this.pullFlowHeight = pullFlowHeight;
    }

    public void setDragDampingRatio(float dragDampingRatio) {
        this.dragDampingRatio = dragDampingRatio;
    }

    public void setDuringAdjustValue(float duringAdjustValue) {
        this.duringAdjustValue = duringAdjustValue;
    }

    public void setRefreshBackTime(long refreshBackTime) {
        this.refreshBackTime = refreshBackTime;
    }

    public void setAdjustTwinkDuring(int adjustTwinkDuring) {
        this.adjustTwinkDuring = adjustTwinkDuring;
        scroller = null;
    }

    public void setAutoLoadingEnable(boolean ableAutoLoading) {
        autoLoadingEnable = ableAutoLoading;
    }

    public void setRefreshShowGravity(@RefreshShowHelper.ShowState int headerShowGravity
            , @RefreshShowHelper.ShowState int footerShowGravity) {
        refreshShowHelper.setHeaderShowGravity(headerShowGravity);
        refreshShowHelper.setFooterShowGravity(footerShowGravity);
    }

    public void setHeaderShowGravity(@RefreshShowHelper.ShowState int headerShowGravity) {
        refreshShowHelper.setHeaderShowGravity(headerShowGravity);
    }

    public void setFooterShowGravity(@RefreshShowHelper.ShowState int footerShowGravity) {
        refreshShowHelper.setFooterShowGravity(footerShowGravity);
    }

    public int getRefreshState() {
        return refreshState;
    }

    public boolean isLoadMoreEnable() {
        return pullLoadMoreEnable;
    }

    public boolean isRefreshEnable() {
        return pullRefreshEnable;
    }

    public boolean isRefreshing() {
        return isRefreshing;
    }

    public interface OnPullListener {
        void onPullChange(float percent);

        void onPullHoldTrigger();

        void onPullHoldUnTrigger();

        void onPullHolding();

        void onPullFinish();

        void onPullReset();

    }

    public static class OnRefreshListener {
        public void onRefresh() {
        }

        public void onLoading() {
        }
    }

    private class PullFrameLayout extends FrameLayout {
        public PullFrameLayout(@NonNull Context context) {
            super(context);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            return event.getActionMasked() == MotionEvent.ACTION_DOWN && targetView != null
                    && !(targetView instanceof NestedScrollingChild) || super.onTouchEvent(event);
        }
    }
}