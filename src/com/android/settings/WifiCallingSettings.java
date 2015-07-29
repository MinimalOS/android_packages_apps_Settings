/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings;

import android.content.Context;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.SwitchPreference;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Switch;

import com.android.ims.ImsConfig;
import com.android.ims.ImsManager;
import com.android.settings.widget.SwitchBar;

/**
 * "Wi-Fi Calling settings" screen.  This preference screen lets you
 * enable/disable Wi-Fi Calling, change mode, enable/disable
 * handover while on roaming.
 */
public class WifiCallingSettings extends SettingsPreferenceFragment
        implements SwitchBar.OnSwitchChangeListener,
        Preference.OnPreferenceChangeListener {

    private static final String TAG = "WifiCallingSettings";

    //String keys for preference lookup
    private static final String BUTTON_WFC_MODE = "wifi_calling_mode";
    private static final String BUTTON_WFC_ROAM = "wifi_calling_roam";

    //UI objects
    private SwitchBar mSwitchBar;
    private Switch mSwitch;
    private ListPreference mButtonWfcMode;
    private SwitchPreference mButtonWfcRoam;

    private final PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        /*
         * Enable/disable controls when in/out of a call and depending on
         * TTY mode and TTY support over VoLTE.
         * @see android.telephony.PhoneStateListener#onCallStateChanged(int,
         * java.lang.String)
         */
        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            final SettingsActivity activity = (SettingsActivity) getActivity();
            boolean isNonTtyOrTtyOnVolteEnabled = ImsManager
                    .isNonTtyOrTtyOnVolteEnabled(activity);
            final SwitchBar switchBar = activity.getSwitchBar();
            boolean isWfcEnabled = switchBar.getSwitch().isChecked()
                    && isNonTtyOrTtyOnVolteEnabled;

            switchBar.setEnabled((state == TelephonyManager.CALL_STATE_IDLE)
                    && isNonTtyOrTtyOnVolteEnabled);

            Preference pref = getPreferenceScreen().findPreference(BUTTON_WFC_MODE);
            int wfcMode = ImsConfig.WfcModeFeatureValueConstants.WIFI_ONLY;
            if (pref != null) {
                pref.setEnabled(isWfcEnabled
                        && (state == TelephonyManager.CALL_STATE_IDLE));
                ListPreference prefWfcMode = (ListPreference) pref;
                wfcMode = Integer.valueOf(prefWfcMode.getValue()).intValue();
            }
            pref = getPreferenceScreen().findPreference(BUTTON_WFC_ROAM);
            if (pref != null) {
                pref.setEnabled(isWfcEnabled
                        && (wfcMode != ImsConfig.WfcModeFeatureValueConstants.WIFI_ONLY)
                        && (state == TelephonyManager.CALL_STATE_IDLE));
            }
        }
    };

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        final SettingsActivity activity = (SettingsActivity) getActivity();

        mSwitchBar = activity.getSwitchBar();
        mSwitch = mSwitchBar.getSwitch();
        mSwitchBar.show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mSwitchBar.hide();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.wifi_calling_settings);

        mButtonWfcMode = (ListPreference) findPreference(BUTTON_WFC_MODE);
        mButtonWfcMode.setOnPreferenceChangeListener(this);

        mButtonWfcRoam = (SwitchPreference) findPreference(BUTTON_WFC_ROAM);
        mButtonWfcRoam.setOnPreferenceChangeListener(this);
    }

    @Override
    public void onResume() {
        super.onResume();

        final Context context = getActivity();

        if (ImsManager.isWfcEnabledByPlatform(context)) {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            tm.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);

            mSwitchBar.addOnSwitchChangeListener(this);
        }

        // NOTE: Buttons will be enabled/disabled in mPhoneStateListener
        boolean wfcEnabled = ImsManager.isWfcEnabledByUser(context)
                && ImsManager.isNonTtyOrTtyOnVolteEnabled(context);
        mSwitch.setChecked(wfcEnabled);

        int wfcMode = ImsManager.getWfcMode(context);
        mButtonWfcMode.setValue(Integer.toString(wfcMode));
        mButtonWfcMode.setSummary(getWfcModeSummary(context, wfcMode));

        mButtonWfcRoam.setChecked(wfcEnabled
                && (wfcMode != ImsConfig.WfcModeFeatureValueConstants.WIFI_ONLY)
                && ImsManager.isWfcRoamingEnabledByUser(context));
    }

    @Override
    public void onPause() {
        super.onPause();

        if (ImsManager.isWfcEnabledByPlatform(getActivity())) {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            tm.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);

            mSwitchBar.removeOnSwitchChangeListener(this);
        }
    }

    /**
     * Listens to the state change of the switch.
     */
    @Override
    public void onSwitchChanged(Switch switchView, boolean isChecked) {
        final Context context = getActivity();

        ImsManager.setWfcSetting(context, isChecked);

        int wfcMode = ImsManager.getWfcMode(context);
        mButtonWfcMode.setSummary(getWfcModeSummary(context, wfcMode));
        mButtonWfcMode.setEnabled(isChecked);
        boolean wfcHandoffEnabled = (wfcMode != ImsConfig.WfcModeFeatureValueConstants.WIFI_ONLY);
        mButtonWfcRoam.setEnabled(isChecked && wfcHandoffEnabled);
        mButtonWfcRoam.setChecked(isChecked && wfcHandoffEnabled
                && ImsManager.isWfcRoamingEnabledByUser(context));
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final Context context = getActivity();
        if (preference == mButtonWfcMode) {
            mButtonWfcMode.setValue((String) newValue);
            int buttonMode = Integer.valueOf((String) newValue);
            int currentMode = ImsManager.getWfcMode(context);
            if (buttonMode != currentMode) {
                ImsManager.setWfcMode(context, buttonMode);
                mButtonWfcMode.setSummary(getWfcModeSummary(context, buttonMode));
            }
            boolean wfcHandoffEnabled =
                    (buttonMode != ImsConfig.WfcModeFeatureValueConstants.WIFI_ONLY);
            mButtonWfcRoam.setEnabled(wfcHandoffEnabled);
            mButtonWfcRoam.setChecked(wfcHandoffEnabled &&
                    ImsManager.isWfcRoamingEnabledByUser(context));
        } else if (preference == mButtonWfcRoam) {
            SwitchPreference wfcRoamPref = (SwitchPreference) preference;
            wfcRoamPref.setChecked(!wfcRoamPref.isChecked());
            ImsManager.setWfcRoamingSetting(context, wfcRoamPref.isChecked());
        }
        return true;
    }

    static int getWfcModeSummary(Context context, int wfcMode) {
        int resId = R.string.wifi_calling_off_summary;
        if (ImsManager.isWfcEnabledByUser(context)) {
            switch (wfcMode) {
                case ImsConfig.WfcModeFeatureValueConstants.WIFI_ONLY:
                    resId = R.string.wfc_mode_wifi_only_summary;
                    break;
                case ImsConfig.WfcModeFeatureValueConstants.CELLULAR_PREFERRED:
                    resId = R.string.wfc_mode_cellular_preferred_summary;
                    break;
                case ImsConfig.WfcModeFeatureValueConstants.WIFI_PREFERRED:
                    resId = R.string.wfc_mode_wifi_preferred_summary;
                    break;
                default:
                    Log.e(TAG, "Unexpected WFC mode value: " + wfcMode);
            }
        }
        return resId;
    }
}
