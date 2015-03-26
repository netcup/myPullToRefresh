package com.zk.mpulltorefreshtest;

import java.util.Arrays;
import java.util.LinkedList;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.zk.mpulltorefreshtest.CustomFrameLayout.PullToRefreshListener;


public class MainActivity extends Activity {

	private String[] mStrings = { 
			"1111111111111111111", "222222222222222222", "333333333333333333", "4444444444444444444",
			"5555555555555555555", "666666666666666666", "777777777777777777", "8888888888888888888", 
			"9999999999999999999", "000000000000000000",
			"aaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbbb", "Abbaye du Mont des Cats", "Abertam", "Abondance", "Ackawi",
			"Acorn", "Adelost", "Affidelice au Chablis", "Afuega'l Pitu", "Airag", "Airedale", "Aisy Cendre",
			"Allgauer Emmentaler" };
	
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        final CustomFrameLayout mCustomFrameLayout = (CustomFrameLayout) findViewById(R.id.customframelayout);
        
        ListView listview = (ListView) findViewById(R.id.listview);
        listview.setDivider(null);
       

		BaseAdapter mAdapter = new MinAdapter(mStrings);
		listview.setAdapter(mAdapter);
		
		mCustomFrameLayout.setOnRefreshListener(new PullToRefreshListener() {
			@Override
			public void onRefresh() {
				
				Runnable refreshTask = new Runnable() {
					@Override
					public void run() {

						// 开启线程调用耗时的Refresh操作
						try {
							Thread.sleep(8000);
						} catch (InterruptedException e) {
							e.printStackTrace();
						}

						// 再回到主线程进行刷新完成的操作
						Runnable finishTask = new Runnable() {

							@Override
							public void run() {
								//主动通知
								mCustomFrameLayout.finishRefreshing();

							}
						};

						mCustomFrameLayout.post(finishTask);

					}

				};

				new Thread(refreshTask).start();

			}
		}, 0);
        
		/*
        mCustomFrameLayout.setOnRefreshListener(new PullToRefreshListener() {
			@Override
			public void onRefresh() {
				try {
					Thread.sleep(8000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}				
			}
		}, 0);
		*/
    }

    public class MinAdapter extends BaseAdapter {

		private String[] titles;


		public MinAdapter(String[] t) {

			titles = t;

		}

		@Override
		public int getCount() {

			return titles.length;
		}

		@Override
		public String getItem(int position) {

			return titles[position];
		}

		@Override
		public long getItemId(int position) {

			return position;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {

			LayoutInflater l = LayoutInflater.from(MainActivity.this);

			View v = l.inflate(R.layout.item, null);

			

			

			((TextView) v.findViewById(R.id.malone1)).setText(titles[position]);

			return v;
		}

	}
}
