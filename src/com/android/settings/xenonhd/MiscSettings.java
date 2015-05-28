/*
* Copyright (C) 2014 Team Horizon
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.android.settings.xenonhd;

import android.app.Activity;
import android.app.ActivityManagerNative;
import android.app.Dialog;
import android.app.IActivityManager;
import android.app.ProgressDialog;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.provider.Settings;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.IWindowManager;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.WindowManagerImpl;

import com.android.internal.util.cm.QSUtils;

import com.android.settings.R;

public class MiscSettings extends SettingsPreferenceFragment implements
Preference.OnPreferenceChangeListener {

    private static final String TAG = "MiscSettings";

    private static final String SHOW_CLEAR_ALL_RECENTS = "show_clear_all_recents";
    private static final String RECENTS_CLEAR_ALL_LOCATION = "recents_clear_all_location";
    private static final String KEY_LCD_DENSITY = "lcd_density";

    private Context mContext;

    private SwitchPreference mRecentsClearAll;
    private ListPreference mRecentsClearAllLocation;
    private ListPreference mLcdDensityPreference;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        addPreferencesFromResource(R.xml.misc_settings);

        PreferenceScreen prefSet = getPreferenceScreen();
        ContentResolver resolver = getActivity().getContentResolver();
        mContext = getActivity().getApplicationContext();
        mRecentsClearAll = (SwitchPreference) prefSet.findPreference(SHOW_CLEAR_ALL_RECENTS);
        mRecentsClearAll.setChecked(Settings.System.getIntForUser(resolver,
        Settings.System.SHOW_CLEAR_ALL_RECENTS, 1, UserHandle.USER_CURRENT) == 1);
        mRecentsClearAll.setOnPreferenceChangeListener(this);

        mRecentsClearAllLocation = (ListPreference) prefSet.findPreference(RECENTS_CLEAR_ALL_LOCATION);
        int location = Settings.System.getIntForUser(resolver,
        Settings.System.RECENTS_CLEAR_ALL_LOCATION, 0, UserHandle.USER_CURRENT);
        mRecentsClearAllLocation.setValue(String.valueOf(location));
        mRecentsClearAllLocation.setOnPreferenceChangeListener(this);
        updateRecentsLocation(location);

        mLcdDensityPreference = (ListPreference) findPreference(KEY_LCD_DENSITY);
        if (mLcdDensityPreference != null) {
            int defaultDensity = getDefaultDensity();
            int currentDensity = getCurrentDensity();
            if (currentDensity < 10 || currentDensity >= 1000) {
                // Unsupported value, force default
                currentDensity = defaultDensity;
            }

            int factor = defaultDensity >= 480 ? 40 : 20;
            int minimumDensity = defaultDensity - 4 * factor;
            int currentIndex = -1;
            String[] densityEntries = new String[7];
            String[] densityValues = new String[7];
            for (int idx = 0; idx < 7; ++idx) {
                int val = minimumDensity + factor * idx;
                int valueFormatResId = val == defaultDensity
                        ? R.string.lcd_density_default_value_format
                        : R.string.lcd_density_value_format;

                densityEntries[idx] = getString(valueFormatResId, val);
                densityValues[idx] = Integer.toString(val);
                if (currentDensity == val) {
                    currentIndex = idx;
                }
            }
            mLcdDensityPreference.setEntries(densityEntries);
            mLcdDensityPreference.setEntryValues(densityValues);
            if (currentIndex != -1) {
                mLcdDensityPreference.setValueIndex(currentIndex);
            }
            mLcdDensityPreference.setOnPreferenceChangeListener(this);
            updateLcdDensityPreferenceDescription(currentDensity);
        }
    }

    private void updateLcdDensityPreferenceDescription(int currentDensity) {
        final int summaryResId = currentDensity == getDefaultDensity()
                ? R.string.lcd_density_default_value_format : R.string.lcd_density_value_format;
        mLcdDensityPreference.setSummary(getString(summaryResId, currentDensity));
    }

    public boolean onPreferenceChange(Preference preference, Object objValue) {
        if (preference == mRecentsClearAll) {
            boolean show = (Boolean) objValue;
            Settings.System.putIntForUser(getActivity().getContentResolver(),
            Settings.System.SHOW_CLEAR_ALL_RECENTS, show ? 1 : 0, UserHandle.USER_CURRENT);
            return true;
        } else if (preference == mRecentsClearAllLocation) {
            int location = Integer.valueOf((String) objValue);
            Settings.System.putIntForUser(getActivity().getContentResolver(),
            Settings.System.RECENTS_CLEAR_ALL_LOCATION, location, UserHandle.USER_CURRENT);
            updateRecentsLocation(location);
            return true;
        } else if (preference == mLcdDensityPreference) {
            try {
                // The value must begin with a decimal number.  It may
                // optionally be follewed by a space and arbitrary text.
                String strValue = (String) objValue;
                int idx = strValue.indexOf(' ');
                if (idx > 0) {
                    strValue = strValue.substring(0, idx);
                }
                int value = Integer.parseInt(strValue);
                writeLcdDensityPreference(preference.getContext(), value);
                updateLcdDensityPreferenceDescription(value);
            }
            catch (NumberFormatException e) {
                Log.e(TAG, "could not persist display density setting", e);
            }
        }
        return false;
    }

    private void writeLcdDensityPreference(final Context context, final int density) {
        final IActivityManager am = ActivityManagerNative.asInterface(
                ServiceManager.checkService("activity"));
        final IWindowManager wm = IWindowManager.Stub.asInterface(ServiceManager.checkService(
                Context.WINDOW_SERVICE));
        AsyncTask<Void, Void, Void> task = new AsyncTask<Void, Void, Void>() {
            @Override
            protected void onPreExecute() {
                ProgressDialog dialog = new ProgressDialog(context);
                dialog.setMessage(getResources().getString(R.string.restarting_ui));
                dialog.setCancelable(false);
                dialog.setIndeterminate(true);
                dialog.show();
            }
            @Override
            protected Void doInBackground(Void... params) {
                // Give the user a second to see the dialog
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    // Ignore
                }

                try {
                    wm.setForcedDisplayDensity(Display.DEFAULT_DISPLAY, density);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to set density to " + density, e);
                }

                // Restart the UI
                try {
                    am.restart();
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to restart");
                }
                return null;
            }
        };
        task.execute();
    }

    private int getDefaultDensity() {
        IWindowManager wm = IWindowManager.Stub.asInterface(ServiceManager.checkService(
                Context.WINDOW_SERVICE));
        try {
            return wm.getInitialDisplayDensity(Display.DEFAULT_DISPLAY);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return DisplayMetrics.DENSITY_DEVICE;
    }

    private int getCurrentDensity() {
        IWindowManager wm = IWindowManager.Stub.asInterface(ServiceManager.checkService(
                Context.WINDOW_SERVICE));
        try {
            return wm.getBaseDisplayDensity(Display.DEFAULT_DISPLAY);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return DisplayMetrics.DENSITY_DEVICE;
    }

    private void updateRecentsLocation(int value) {
        ContentResolver resolver = getContentResolver();
        Resources res = getResources();
        int summary = -1;

        Settings.System.putInt(resolver, Settings.System.RECENTS_CLEAR_ALL_LOCATION, value);

        if (value == 0) {
            Settings.System.putInt(resolver, Settings.System.RECENTS_CLEAR_ALL_LOCATION, 0);
            summary = R.string.recents_clear_all_location_top_right;
        } else if (value == 1) {
            Settings.System.putInt(resolver, Settings.System.RECENTS_CLEAR_ALL_LOCATION, 1);
            summary = R.string.recents_clear_all_location_top_left;
        } else if (value == 2) {
            Settings.System.putInt(resolver, Settings.System.RECENTS_CLEAR_ALL_LOCATION, 2);
            summary = R.string.recents_clear_all_location_top_center;
        } else if (value == 3) {
            Settings.System.putInt(resolver, Settings.System.RECENTS_CLEAR_ALL_LOCATION, 3);
            summary = R.string.recents_clear_all_location_bottom_right;
        } else if (value == 4) {
            Settings.System.putInt(resolver, Settings.System.RECENTS_CLEAR_ALL_LOCATION, 4);
            summary = R.string.recents_clear_all_location_bottom_left;
        } else if (value == 5) {
            Settings.System.putInt(resolver, Settings.System.RECENTS_CLEAR_ALL_LOCATION, 5);
            summary = R.string.recents_clear_all_location_bottom_center;
        }
        if (mRecentsClearAllLocation != null && summary != -1) {
            mRecentsClearAllLocation.setSummary(res.getString(summary));
        }
    }
}
