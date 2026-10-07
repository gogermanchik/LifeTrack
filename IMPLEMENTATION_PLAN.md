<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical" android:padding="16dp" android:background="@drawable/widget_background">
 <TextView android:id="@+id/widget_title" android:layout_width="match_parent" android:layout_height="wrap_content" android:text="@string/app_name" android:textColor="#DDF1E2" android:textSize="14sp"/>
 <TextView android:id="@+id/widget_main" android:layout_width="match_parent" android:layout_height="wrap_content" android:textColor="#FFFFFF" android:textSize="24sp" android:textStyle="bold" android:maxLines="2"/>
 <TextView android:id="@+id/widget_secondary" android:layout_width="match_parent" android:layout_height="wrap_content" android:textColor="#DDF1E2" android:textSize="13sp" android:maxLines="2"/>
 <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="horizontal">
  <TextView android:id="@+id/widget_food" android:layout_width="0dp" android:layout_weight="1" android:layout_height="48dp" android:gravity="center" android:text="@string/widget_food" android:textColor="#FFFFFF" android:textSize="14sp"/>
  <TextView android:id="@+id/widget_water" android:layout_width="0dp" android:layout_weight="1" android:layout_height="48dp" android:gravity="center" android:text="@string/widget_water" android:textColor="#FFFFFF" android:textSize="14sp"/>
 </LinearLayout>
</LinearLayout>
