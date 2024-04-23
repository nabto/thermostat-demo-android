# Making a new app using the sharedcode skeleton
This is a step by step guide for using the same skeleton framework in the sharedcode module to build an app.

## Creating a new module
1. Open the repository in Android Studio.
2. In the top menu, choose `New > New Module`
3. Fill out the form and press next
4. Choose `No Activity` for the template and continue.

This should create a new folder next to the other modules with the name you gave it. The module should have no activity because it will use an activity inherited from the sharedcode module.

Next we will add dependencies.
1. Open the build.gradle (or build.gradle.kts if you use Kotlin DSL) file that is inside your module's folder.
2. In the dependencies section, add the following dependencies
```
// For Groovy
implementation project([path: ":sharedcode"])

// For kotlin DSL
implementation(project(mapOf("path" to ":sharedcode")))
```

## AndroidManifest.xml
Now we will edit the Android manifest file to give permissions for mDNS and internet connection, and we will also add the activity from the sharedcode module here. Your `AndroidManifest.xml` file should be in `src/main/AndroidManifest.xml` in your module's folder.

Add the following permissions
```xml
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
```

On the `application` tag, add a key that references the sharedcode's NabtoAndroidApplication class
```xml
    <application
        android:name="com.nabto.edge.sharedcode.NabtoAndroidApplication">
    </application>
```

Inside the `application` tag, reference the `AppMainActivity` from sharedcode.
```xml
    <activity
        android:name="com.nabto.edge.sharedcode.AppMainActivity"
        android:windowSoftInputMode="adjustPan"
        android:exported="true">
        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent-filter>
        <nav-graph android:value="@navigation/settings"/>
    </activity>
```

All in all, your AndroidManifest.xml file may look as follows
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
    <application
        android:name="com.nabto.edge.sharedcode.NabtoAndroidApplication"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.NabtoDemo">
        <activity
            android:name="com.nabto.edge.sharedcode.AppMainActivity"
            android:windowSoftInputMode="adjustPan"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <nav-graph android:value="@navigation/settings"/>
        </activity>
    </application>
</manifest>
```

## Creating a fragment and navigation graph
Now right click on your module in the project explorer and select `New > Fragment > Fragment (Blank)` (or another type of fragment if you prefer). Name it for example `DevicePageFragment`. This fragment will be the one that'll be displayed when the user navigates to a specific device.

Next we must ensure that the `sharedcode` skeleton will use your fragment. The skeleton will use whichever navigation graph is defined in a `device.xml` file in your resources folder.

1. Right click your res folder and select New > Android Resource File.
2. For file name, enter `device.xml`
3. For resource type, select `navigation`
4. IMPORTANT: You *must* name the file `device.xml` and it must be a navigation resource type. This will override the default navigation files used inside the sharedcode module and use your `device.xml` instead. This way we can ensure that your fragment is being pointed to.

In the device.xml file you may add your fragment, if you for example called it DevicePageFragment then your navigation file may look as follows
```
<?xml version="1.0" encoding="utf-8"?>
<navigation xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/nav_device"
    app:startDestination="@id/devicePageFragment">
    <fragment
        android:id="@+id/devicePageFragment"
        android:name="com.nabto.edge.thermostatdemo.DevicePageFragment"/>
</navigation>
```

Note that you are free to make this navigation file however you want. The sharedcode module will simply go to whichever navigation graph is defined in `device.xml`.Note the `app:startDestination` tag is what sends you into your fragment.

This concludes the tutorial on how to add a module to this repository. You may want to take a look at e.g. thermostat to see that it works in this exact way, and use it for reference.
