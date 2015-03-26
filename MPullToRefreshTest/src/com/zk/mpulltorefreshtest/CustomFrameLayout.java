package com.zk.mpulltorefreshtest;

import java.lang.reflect.Field;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.AnimationDrawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.AbsListView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

public class CustomFrameLayout extends FrameLayout implements OnTouchListener {

	// 记录手指触摸的位置X，Y坐标
	private float mLastMotionX = .0F;
	private float mLastMotionY = .0F;

	// 记录手指触摸的X和Y偏移量（用于计算rameLayout中视图的X和Y偏移量）,下面的数据大概是他的一半。
	private float mDeltaY;

	// 记录内容FrameLayout中视图的X和Y偏移量（相对于坐标原点的偏移量0,0）
	private float mListLayoutY;

	// 回滚到原点的任务
	private ScrollToHomeRunnable mScrollToHomeRunnable;

	// 当前出于什么状态：
	private enum State {
		STATUS_REFRESHING, // 正在刷新状态，这个状态已经单独出来，这里其实没有什么实际用处。如果把正在刷新状态放在这里会很容易被其他动作给改变掉。
		STATUS_PULL_TO_REFRESH, // 没有到达Loading高度的回滚到原点的状态，很短暂。会屏蔽listView的滚动。
		STATUS_RELEASE_TO_REFRESH, // 超过Loading高度的回滚到Loading高度的状态，很短暂。会屏蔽listView的滚动。
		PULLING_VERTICAL, // 正在拉动状态，手在屏幕向上拉和向下拉都是这个状态。在这个状态下如果符合向上拉向下拉会屏蔽listView的滚动，如果不符合则不屏蔽。
		STATUS_REFRESH_FINISHED, // 获取数据完成，完成刷新状态之后回滚到原点的状态。这个回滚状态要求优先级很高，不管怎么样都要回滚到原点，如果有其他的回滚都要取消。很短暂。会屏蔽listView的滚动。
		NORMAL, // 正常状态，这个状态是内容回归到原点。
	}

	// 是否正在刷新，这个是单独出来的状态，需要长时间的保存。
	private boolean isRefreshing = false;

	// 当前状态
	private State mState;

	// Loading所在的Layout
	private FrameLayout mLoadingLayout;
	// 列表数据所在的Layout
	private FrameLayout mListLayout;

	//这些可以定制
	// Loading里面的动画
	private ImageView mLoadingImg;
	// Loading里面的文字
	private TextView mLoadingText;

	/**
	 * 是否已加载过一次layout，这里onLayout中的初始化只需加载一次
	 */
	private boolean loadOnce;

	/**
	 * Loading的高度
	 */
	private int hideHeaderHeight;

	private static final String TAG = "PullToRefresh";

	/**
	 * 为了防止不同界面的下拉刷新在上次更新时间上互相有冲突，使用id来做区分
	 */
	private int mId = -1;

	/**
	 * 下拉刷新的回调接口
	 */
	private PullToRefreshListener mListener;

	/**
	 * 需要去下拉刷新的ListView
	 */
	private ListView listView;

	/**
	 * 当前是否可以下拉，只有ListView滚动到头的时候才允许下拉
	 */
	private boolean ableToPull;
	

	@SuppressLint("NewApi")
	public CustomFrameLayout(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		init(context);
	}

	public CustomFrameLayout(Context context, AttributeSet attrs) {
		super(context, attrs);
		init(context);
	}

	public CustomFrameLayout(Context context) {
		super(context);
		init(context);
	}

	private void init(Context context) {
		mState = State.NORMAL;

	}

	/**
	 * 进行一些关键性的初始化操作，比如：将下拉头向上偏移进行隐藏，给ListView注册touch事件。
	 */
	@Override
	protected void onLayout(boolean changed, int l, int t, int r, int b) {
		super.onLayout(changed, l, t, r, b);
		if (changed && !loadOnce) {
			mLoadingLayout = (FrameLayout) getChildAt(0);
			mListLayout = (FrameLayout) getChildAt(1);

			hideHeaderHeight = mLoadingLayout.getHeight();

			listView = (ListView) mListLayout.getChildAt(0);
			listView.setOnTouchListener(this);

			mLoadingImg = (ImageView) mLoadingLayout.findViewById(R.id.loading_img);
			mLoadingText = (TextView) mLoadingLayout.findViewById(R.id.loading_text);

			loadOnce = true;
		}
	}
	
	/**
	 * 检测用户滑动的距离，方向等，然后调用scrollTo来让整个View偏移
	 */
	private boolean myOnTouchEvent(MotionEvent event) {

		//Log.d(TAG,"onTouchEvent");

		// 判断是否允许下拉
		setIsAbleToPull(event);

		// 不能下拉 不能影响listview的正常滑动
		if (!ableToPull) {
			Log.d(TAG,"OnTouch=false(1),action="+event.getAction());
			return false;
		}

		if (mState != State.STATUS_PULL_TO_REFRESH
				&& mState != State.STATUS_RELEASE_TO_REFRESH
				&& mState != State.STATUS_REFRESH_FINISHED) {

			int action = event.getAction();
			switch (action) {
			case MotionEvent.ACTION_DOWN:

				/**
				 * 按下时 记录X，Y坐标，恢复mDeltaX和mDeltaY为0
				 */
				mLastMotionX = event.getRawX();
				mLastMotionY = event.getRawY();
				mState = State.NORMAL;
				
				break;

			case MotionEvent.ACTION_MOVE:

				/**
				 * 滑动时 根据当前触摸点 - 上次记录的x或者y坐标，得到增量，然后应用到scrollTo方法上去， 然后重新记录x，y坐标
				 */
				float innerDeltaY = event.getRawY() - mLastMotionY;// 记录Y的差值
				float innerDeltaX = event.getRawX() - mLastMotionX;// 记录X的差值
				float absInnerDeltaY = Math.abs(innerDeltaY);// Y差值绝对值
				float absInnerDeltaX = Math.abs(innerDeltaX);// X差值绝对值

				if (absInnerDeltaY > 0) {				

					Log.d(TAG, "mDeltaY=" + mDeltaY);

					if (innerDeltaY > 1.0F) {// innerDeltaY为正数，用户正在向下拉动，1.0F可看做阈值，下面类似
						mDeltaY -= absInnerDeltaY;// 注意这个地方是-=，即累减的过程
						Log.d(TAG, "向下拉动：" + absInnerDeltaY);
						pull(mDeltaY);

						mState = State.PULLING_VERTICAL;// 滑动状态：正在垂直拉动
						
						// 向上拉动的情况忽略不计
					} else if (innerDeltaY < -1.0F) {// innerDeltaY为负数，用户正在向上拉动
						Log.d(TAG, "向上拉动：" + absInnerDeltaY);

						mDeltaY += absInnerDeltaY;

						//超过了坐标原点(0,0)：1.使内容保持在原点，2.就不要屏蔽掉listview的滚动。
						if (mDeltaY >= 0) {
							mDeltaY = 0;
							pull(0);													
							mState = State.NORMAL;
							Log.d(TAG,"OnTouch=false(2),action="+event.getAction());
							return false;
						}

						pull(mDeltaY);
						
						mState = State.PULLING_VERTICAL;// 滑动状态：正在垂直拉动

					}
				}
				// 重新记录新的坐标值
				mLastMotionX = event.getRawX();
				mLastMotionY = event.getRawY();
				
				updateHeaderView();
				
				
				
				break;

			case MotionEvent.ACTION_UP:
				/**
				 * 用户松开手指之后，View自动回到偏移量为0的位置
				 */
				
				smoothScrollTo(mDeltaY);
				
				if(mState == State.NORMAL){
					Log.d(TAG,"OnTouch=false(3),action="+event.getAction());
					return false;
				}
					
				break;
			}

		}

		
		
		if (mState == State.PULLING_VERTICAL
				|| mState == State.STATUS_PULL_TO_REFRESH
				|| mState == State.STATUS_RELEASE_TO_REFRESH
				|| mState == State.STATUS_REFRESH_FINISHED) {
			Log.d(TAG,"OnTouch=true(1),action="+event.getAction());
			return true;
		}

		Log.d(TAG,"OnTouch=false(4),action="+event.getAction());
		
		return false;
	}

	/**
	 * 根据当前ListView的滚动状态来设定 {@link #ableToPull}
	 * 的值，每次都需要在onTouch中第一个执行，这样可以判断出当前应该是滚动ListView，还是应该进行下拉。
	 * 
	 * @param event
	 */
	private void setIsAbleToPull(MotionEvent event) {
		View firstChild = listView.getChildAt(0);
		if (firstChild != null) {
			int firstVisiblePos = listView.getFirstVisiblePosition();
			if (firstVisiblePos == 0 && firstChild.getTop() == 0) {

				if (!ableToPull) {
					// 这里一定要初始化一下，为什么：
					// 当listview向上滚动时，滚过初始化位置，并且向上翻几条记录之后再往下滚时，
					// 这个时候触发向下拉动时,滚过初始化位置时，
					// 由于setIsAbleToPull()这时才返回true,如果不初始化下，得到向下拉动距离innerDeltaY会非常大。会出现跳动一下。

					mLastMotionX = event.getRawX();
					mLastMotionY = event.getRawY();
				}

				// 如果首个元素的上边缘，距离父布局值为0，就说明ListView滚动到了最顶部，此时应该允许下拉刷新
				ableToPull = true;
			} else {

				ableToPull = false;
			}
		} else {
			// 如果ListView中没有元素，也应该允许下拉刷新
			ableToPull = true;
		}
	}

	/**
	 * 用户触摸阶段（ACTION_MOVE）的拉动方法，除以2.0将拉动的距离缩放，
	 * 这里代表如果用户已知从上滑到下面，那么整个View最多偏移半个屏幕。
	 * 
	 * @param diff
	 */
	private void pull(float diff) {
		int value = Math.round(diff / 2.0F);// diff就是偏移量，除以2.0相当于一个缩放

		Log.d(TAG, "pull:" + value);
		mListLayoutY = value;

		mListLayout.scrollTo(0, value);// 注意这里是核心了，Y方向上移动value距离，X方向上保持不变
		
//		if(value != 0){
//			
////			listView.setPressed(false);
////			listView.setFocusable(false);
////			listView.setFocusableInTouchMode(false);
//			
//			listView.setClickable(false);
//			listView.setLongClickable(false);
//		}
		
		/**
		 * 加了下面这段代码之后，能解决触摸下拉滑动时press背景色一直是按下状态的背景的问题。
		 * 正常的是按下是按下状态的背景色，但是滑动时还原到正常背景。
		 * 同时也带一个bug：当一直触摸时滑动到顶部，listview无法再往上滑动。只有放开触摸才能再往下滑动。
		 * 
		 * PullToRefresh当非loading状态时也是有这个问题存在。当他loading滑动时是正常的是因为他把Loading放在listview中第一个headerview中。
		 * 
		 * 因为有这个bug存在，反而解决了另一个bug:当Loading状态时慢慢地向上滑动到达顶部时再往上滑会抖动一下。
		 */
		if(value != 0){
			clearContentViewEvents();
		}

	}

	private void smoothScrollTo(float diff) {

		// Log.d(TAG,"smoothScrollTo:mLastMotionY="+mLastMotionY+",diff="+diff);

		int value = Math.round(diff / 2.0F);
		mListLayoutY = value;

		Log.d(TAG, "smoothScrollTo:value=" + value + ",hideHeaderHeight="
				+ hideHeaderHeight);
		
		if(Math.abs(value) > 0){

			if (Math.abs(value) > hideHeaderHeight) {

				mState = State.STATUS_RELEASE_TO_REFRESH;
				// 滚动到高度所在的位置，然后刷新
				mScrollToHomeRunnable = new ScrollToHomeRunnable(value,
						-hideHeaderHeight, mState);
	
				post(mScrollToHomeRunnable);// view自身有一个post方法，我们提交一个scrollTo的任务给它
			} else if(Math.abs(value) == hideHeaderHeight) {
				if(!isRefreshing){
					mState = State.STATUS_RELEASE_TO_REFRESH;
					// 滚动到高度所在的位置，然后刷新
					mScrollToHomeRunnable = new ScrollToHomeRunnable(value,
							-hideHeaderHeight, mState);
		
					post(mScrollToHomeRunnable);// view自身有一个post方法，我们提交一个scrollTo的任务给它
				}
			}else{
	
				mState = State.STATUS_PULL_TO_REFRESH;
				// 直接滚动到初始处
				mScrollToHomeRunnable = new ScrollToHomeRunnable(value, 0, mState);
	
				post(mScrollToHomeRunnable);// view自身有一个post方法，我们提交一个scrollTo的任务给它
			}
			
			updateHeaderView();
		}
	}

	/**
	 * 当所有的刷新逻辑完成后，记录调用一下，否则你的ListView将一直处于正在刷新状态。
	 */
	public void finishRefreshing() {

		int value = Math.round(mListLayoutY);

		Log.d(TAG, "finishRefreshing,value=" + value);

		isRefreshing = false;
		
		if(value == 0){
			mState = State.NORMAL;
		}else{
			mState = State.STATUS_REFRESH_FINISHED;
	
			mScrollToHomeRunnable = new ScrollToHomeRunnable(value, 0, mState);
	
			post(mScrollToHomeRunnable);// view自身有一个post方法，我们提交一个scrollTo的任务给它
		}
		
		updateHeaderView();
	}

	/**
	 * 更新下拉头中的信息，包括以下三种状态：
	 * 1.正在刷新的优先级最高
	 * 2.刷新完成后的状态。第二优先级
	 * 3.下拉和释放的状态属于第三优先级。
	 * 其他的状态忽略不计
	 */
	private void updateHeaderView() {	
		
		if (isRefreshing) {			
			mLoadingImg.setImageResource(R.drawable.animation);
			AnimationDrawable animationDrawable = (AnimationDrawable) mLoadingImg.getDrawable();
			animationDrawable.start();   //开始动画  
			mLoadingText.setText("正在刷新数据");
		}else{
			
			if(mState == State.STATUS_REFRESH_FINISHED){
				mLoadingImg.setImageResource(R.drawable.refresh_005);
				mLoadingText.setText("刚刚更新");
			}else{
				
				if(mListLayoutY <= -hideHeaderHeight){
					mLoadingImg.setImageResource(R.drawable.refresh_005);
					mLoadingText.setText("15分钟前更新，释放进行刷新");
				}else{
					mLoadingImg.setImageResource(R.drawable.refresh_005);
					mLoadingText.setText("15分钟前更新，下拉进行刷新");
				}
			}
		}
	}

	final class ScrollToHomeRunnable implements Runnable {

		private final Interpolator mInterpolator;
		private int target;
		private int current;
		private State state;
		private long mStartTime = -1;

		public ScrollToHomeRunnable(int current, int target, State state) {
			this.target = target;
			this.current = current;
			this.state = state;

			// 初始化DecelerateInterpolator差值器。
			mInterpolator = new DecelerateInterpolator();
		}

		@Override
		public void run() {
			/*
			//这里主要是怕有多个回滚有冲突，尽量只有一个不断postDelayed的消息在不断传递。
			//优先级最高的是刷新完成后的回滚，当他回滚时，其他的回滚都取消掉
			if (mState == State.STATUS_REFRESH_FINISHED && state != mState) {
				return;
			}
			*/
			if (mStartTime == -1) {
				mStartTime = System.currentTimeMillis();
			} else {
				long normalizedTime = (1000 * (System.currentTimeMillis() - mStartTime)) / 200;
				normalizedTime = Math.max(Math.min(normalizedTime, 1000), 0);
				final int delta = Math.round((current - target)
						* mInterpolator
								.getInterpolation(normalizedTime / 1000f));

				current = current - delta;

				mListLayout.scrollTo(0, current);
				mListLayoutY = current;

				mDeltaY = mListLayoutY * 2;
				
				Log.d(TAG,"scrollTo(0,"+current+")");
			}

			//这里主要是怕有多个回滚有冲突，尽量只有一个不断postDelayed的消息在不断传递。
			//优先级最高的是刷新完成后的回滚，当他回滚时，其他的回滚都取消掉
			if (mState == State.STATUS_REFRESH_FINISHED && state != mState) {
				return;
			}

			if (current != target) {
				postDelayed(this, 16);// 没有回到原点：在经过16毫秒之后继续postDelayed这个任务
			} else {

				// 这里有问题
				if (target != 0 && isRefreshing == false) {
					mState = State.STATUS_REFRESHING;
					isRefreshing = true;
					updateHeaderView();
					
					
					//另一种方法，让调用者来处理线程。
					mListener.onRefresh();
/*					
					//另一种方法，自己来处理线程。
					Runnable refreshTask = new Runnable() {

						@Override
						public void run() {

							
//							// 开启线程调用耗时的Refresh操作
//							mListener.onRefresh();
							
							try {
								Thread.sleep(8000);
							} catch (InterruptedException e) {
							}

							// 再回到主线程进行刷新完成的操作
							Runnable finishTask = new Runnable() {

								@Override
								public void run() {
									finishRefreshing();

								}
							};

							CustomFrameLayout.this.post(finishTask);

						}

					};

					new Thread(refreshTask).start();
*/					

				} else {
					mState = State.NORMAL;// 回到原点，mState置为NORMAL状态
				}
			}
		}
	}

	@Override
	public boolean onTouch(View v, MotionEvent event) {
		return myOnTouchEvent(event);
	}

	/**
	 * 下拉刷新的监听器，使用下拉刷新的地方应该注册此监听器来获取刷新回调。
	 */
	public interface PullToRefreshListener {

		/**
		 * 刷新时会去回调此方法，在方法内编写具体的刷新逻辑。注意此方法是在子线程中调用的， 你可以不必另开线程来进行耗时操作。
		 */
		void onRefresh();

	}

	/**
	 * 给下拉刷新控件注册一个监听器。
	 * 
	 * @param listener
	 *            监听器的实现。
	 * @param id
	 *            为了防止不同界面的下拉刷新在上次更新时间上互相有冲突， 请不同界面在注册下拉刷新监听器时一定要传入不同的id。
	 */
	public void setOnRefreshListener(PullToRefreshListener listener, int id) {
		mListener = listener;
		mId = id;
	}
	
	/** 
     * 通过反射修改字段去掉长按事件和点击事件 
     */  
    private void clearContentViewEvents()  
    {  
        try  
        {  
            Field[] fields = AbsListView.class.getDeclaredFields();  
            for (int i = 0; i < fields.length; i++)  
                if (fields[i].getName().equals("mPendingCheckForLongPress"))  
                {  
                    // mPendingCheckForLongPress是AbsListView中的字段，通过反射获取并从消息列表删除，去掉长按事件  
                    fields[i].setAccessible(true);  
                    listView.getHandler().removeCallbacks((Runnable) fields[i].get(listView));  
                } else if (fields[i].getName().equals("mTouchMode")) {  
                    // TOUCH_MODE_REST = -1， 这个可以去除点击事件  
                    fields[i].setAccessible(true);  
                    fields[i].set(listView, -1);  
                }  
            /*
            // 去掉焦点  
            ((AbsListView) listView).getSelector().setState(new int[]  
            { 0 }); 
            */ 
        } catch (Exception e)  
        {  
            Log.d(TAG, "error : " + e.toString());  
        }  
    }  
}
