<manifest xmlns:android="http://schemas.android.com/apk/res/android">
 <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
 <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
 <uses-permission android:name="android.permission.CAMERA"/>
 <uses-permission android:name="android.permission.INTERNET"/>
 <uses-permission android:name="android.permission.health.READ_STEPS"/>
 <uses-permission android:name="android.permission.health.READ_DISTANCE"/>
 <uses-permission android:name="android.permission.health.READ_ACTIVE_CALORIES_BURNED"/>
 <uses-permission android:name="android.permission.health.READ_TOTAL_CALORIES_BURNED"/>
 <uses-permission android:name="android.permission.health.READ_EXERCISE"/>
 <uses-permission android:name="android.permission.health.READ_SLEEP"/>
 <uses-permission android:name="android.permission.health.READ_HEART_RATE"/>
 <uses-permission android:name="android.permission.health.READ_RESTING_HEART_RATE"/>
 <uses-permission android:name="android.permission.health.READ_OXYGEN_SATURATION"/>
 <uses-permission android:name="android.permission.health.READ_VO2_MAX"/>
 <uses-permission android:name="android.permission.health.READ_WEIGHT"/>
 <uses-permission android:name="android.permission.health.READ_HEIGHT"/>
 <uses-permission android:name="android.permission.health.READ_BODY_FAT"/>
 <uses-permission android:name="android.permission.health.READ_NUTRITION"/>
 <uses-permission android:name="android.permission.health.READ_HYDRATION"/>
 <uses-permission android:name="android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"/>

 <uses-feature android:name="android.hardware.camera" android:required="false"/>
 <queries><intent><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent><package android:name="com.google.android.apps.healthdata"/><package android:name="com.sec.android.app.shealth"/></queries>
 <application android:label="@string/app_name" android:icon="@drawable/ic_launcher" android:allowBackup="false" android:fullBackupContent="false" android:dataExtractionRules="@xml/data_extraction_rules" android:theme="@style/AppTheme" android:supportsRtl="true">
 <activity android:name=".MainActivity" android:launchMode="singleTop" android:exported="true" android:windowSoftInputMode="adjustResize"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity>
 <receiver android:name=".data.ReminderReceiver" android:exported="false"/>
 <receiver android:name=".data.BootReceiver" android:exported="true"><intent-filter><action android:name="android.intent.action.BOOT_COMPLETED"/></intent-filter></receiver>
 <provider android:name="androidx.core.content.FileProvider" android:authorities="com.lifetrack.photos" android:exported="false" android:grantUriPermissions="true"><meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/photo_paths"/></provider>
 <activity android:name=".HealthPrivacyActivity" android:exported="true"><intent-filter><action android:name="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE"/></intent-filter></activity>
 <activity-alias android:name=".HealthPermissionUsage" android:exported="true" android:targetActivity=".HealthPrivacyActivity" android:permission="android.permission.START_VIEW_PERMISSION_USAGE"><intent-filter><action android:name="android.intent.action.VIEW_PERMISSION_USAGE"/><category android:name="android.intent.category.HEALTH_PERMISSIONS"/></intent-filter></activity-alias>
 <receiver android:name=".data.TodayWidget" android:exported="false"><intent-filter><action android:name="android.appwidget.action.APPWIDGET_UPDATE"/></intent-filter><meta-data android:name="android.appwidget.provider" android:resource="@xml/widget_today"/></receiver>
 <service android:name=".banking.BankNotificationListenerService" android:label="@string/app_name" android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" android:exported="true"><intent-filter><action android:name="android.service.notification.NotificationListenerService"/></intent-filter></service>
 <receiver android:name=".banking.BankConfirmReceiver" android:exported="false"/>
 </application>
</manifest>