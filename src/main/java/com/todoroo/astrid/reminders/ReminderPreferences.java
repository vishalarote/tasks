/**
 * Copyright (c) 2012 Todoroo Inc
 *
 * See the file "LICENSE" for the full license governing this code.
 */
package com.todoroo.astrid.reminders;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceManager;

import org.tasks.R;
import org.tasks.activities.TimePickerActivity;
import org.tasks.billing.PurchaseHelper;
import org.tasks.billing.PurchaseHelperCallback;
import org.tasks.dialogs.DialogBuilder;
import org.tasks.dialogs.ThemePickerDialog;
import org.tasks.injection.ActivityComponent;
import org.tasks.injection.InjectingPreferenceActivity;
import org.tasks.preferences.ActivityPermissionRequestor;
import org.tasks.preferences.Device;
import org.tasks.preferences.PermissionChecker;
import org.tasks.preferences.PermissionRequestor;
import org.tasks.preferences.Preferences;
import org.tasks.scheduling.GeofenceSchedulingIntentService;
import org.tasks.scheduling.ReminderSchedulerIntentService;
import org.tasks.themes.LEDColor;
import org.tasks.themes.ThemeCache;
import org.tasks.time.DateTime;
import org.tasks.ui.TimePreference;

import javax.inject.Inject;

import timber.log.Timber;

import static com.todoroo.andlib.utility.AndroidUtilities.atLeastJellybean;
import static com.todoroo.andlib.utility.AndroidUtilities.atLeastMarshmallow;
import static org.tasks.dialogs.NativeThemePickerDialog.newNativeThemePickerDialog;

public class ReminderPreferences extends InjectingPreferenceActivity implements ThemePickerDialog.ThemePickerCallback, PurchaseHelperCallback {

    private static final int REQUEST_QUIET_START = 10001;
    private static final int REQUEST_QUIET_END = 10002;
    private static final int REQUEST_DEFAULT_REMIND = 10003;
    private static final int REQUEST_PURCHASE = 10004;
    private static final String FRAG_TAG_LED_PICKER = "frag_tag_led_picker";

    @Inject Device device;
    @Inject ActivityPermissionRequestor permissionRequestor;
    @Inject PermissionChecker permissionChecker;
    @Inject DialogBuilder dialogBuilder;
    @Inject PurchaseHelper purchaseHelper;
    @Inject Preferences preferences;
    @Inject ThemeCache themeCache;

    private CheckBoxPreference fieldMissedCalls;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.preferences_reminders);

        rescheduleNotificationsOnChange(
                R.string.p_rmd_time,
                R.string.p_doze_notifications,
                R.string.p_rmd_enable_quiet,
                R.string.p_rmd_quietStart,
                R.string.p_rmd_quietEnd);
        resetGeofencesOnChange(
                R.string.p_geofence_radius,
                R.string.p_geofence_responsiveness);

        fieldMissedCalls = (CheckBoxPreference) findPreference(getString(R.string.p_field_missed_calls));
        fieldMissedCalls.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                return newValue != null && (!(boolean) newValue || permissionRequestor.requestMissedCallPermissions());
            }
        });
        fieldMissedCalls.setChecked(fieldMissedCalls.isChecked() && permissionChecker.canAccessMissedCallPermissions());

        initializeRingtonePreference();
        initializeTimePreference(getDefaultRemindTimePreference(), REQUEST_DEFAULT_REMIND);
        initializeTimePreference(getQuietStartPreference(), REQUEST_QUIET_START);
        initializeTimePreference(getQuietEndPreference(), REQUEST_QUIET_END);

        findPreference(getString(R.string.p_led_color)).setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                showLEDColorPicker();
                return false;
            }
        });

        requires(atLeastJellybean(), R.string.p_rmd_notif_actions_enabled, R.string.p_notification_priority);
        requires(atLeastMarshmallow(), R.string.p_doze_notifications);
        requires(device.supportsLocationServices(), R.string.geolocation_reminders);

        updateLEDColor();
    }

    private void rescheduleNotificationsOnChange(int... resIds) {
        for (int resId : resIds) {
            findPreference(getString(resId)).setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    startService(new Intent(ReminderPreferences.this, ReminderSchedulerIntentService.class));
                    return true;
                }
            });
        }
    }

    private void resetGeofencesOnChange(int... resIds) {
        for (int resId : resIds) {
            findPreference(getString(resId)).setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    startService(new Intent(ReminderPreferences.this, GeofenceSchedulingIntentService.class));
                    return true;
                }
            });
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PermissionRequestor.REQUEST_CONTACTS) {
            for (int grantResult : grantResults) {
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
            }
            fieldMissedCalls.setChecked(true);
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private void initializeTimePreference(final TimePreference preference, final int requestCode) {
        preference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference ignored) {
                final DateTime current = new DateTime().withMillisOfDay(preference.getMillisOfDay());
                startActivityForResult(new Intent(ReminderPreferences.this, TimePickerActivity.class) {{
                    putExtra(TimePickerActivity.EXTRA_TIMESTAMP, current.getMillis());
                }}, requestCode);
                return true;
            }
        });
    }

    private void initializeRingtonePreference() {
        Preference.OnPreferenceChangeListener ringtoneChangedListener = new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object value) {
                if ("".equals(value)) {
                    preference.setSummary(R.string.silent);
                } else {
                    Ringtone ringtone = RingtoneManager.getRingtone(ReminderPreferences.this, value == null
                            ? RingtoneManager.getActualDefaultRingtoneUri(getApplicationContext(), RingtoneManager.TYPE_NOTIFICATION)
                            : Uri.parse((String) value));
                    preference.setSummary(ringtone == null ? "" : ringtone.getTitle(ReminderPreferences.this));
                }
                return true;
            }
        };

        String ringtoneKey = getString(R.string.p_rmd_ringtone);
        Preference ringtonePreference = findPreference(ringtoneKey);
        ringtonePreference.setOnPreferenceChangeListener(ringtoneChangedListener);
        ringtoneChangedListener.onPreferenceChange(ringtonePreference, PreferenceManager.getDefaultSharedPreferences(this)
                .getString(ringtoneKey, null));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_QUIET_START) {
            if (resultCode == RESULT_OK) {
                getQuietStartPreference().handleTimePickerActivityIntent(data);
            }
        } else if (requestCode == REQUEST_QUIET_END) {
            if (resultCode == RESULT_OK) {
                getQuietEndPreference().handleTimePickerActivityIntent(data);
            }
        } else if (requestCode == REQUEST_DEFAULT_REMIND) {
            if (resultCode == RESULT_OK) {
                getDefaultRemindTimePreference().handleTimePickerActivityIntent(data);
            }
        } else if (requestCode == REQUEST_PURCHASE) {
            if (resultCode == RESULT_OK) {
                purchaseHelper.handleActivityResult(this, requestCode, resultCode, data);
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private TimePreference getQuietStartPreference() {
        return getTimePreference(R.string.p_rmd_quietStart);
    }

    private TimePreference getQuietEndPreference() {
        return getTimePreference(R.string.p_rmd_quietEnd);
    }

    private TimePreference getDefaultRemindTimePreference() {
        return getTimePreference(R.string.p_rmd_time);
    }

    private TimePreference getTimePreference(int resId) {
        return (TimePreference) findPreference(getString(resId));
    }

    @Override
    public void inject(ActivityComponent component) {
        component.inject(this);
    }

    @Override
    public void themePicked(ThemePickerDialog.ColorPalette palette, int index) {
        preferences.setInt(R.string.p_led_color, index);
        updateLEDColor();
    }

    @Override
    public void initiateThemePurchase() {
        purchaseHelper.purchase(dialogBuilder, this, getString(R.string.sku_themes), getString(R.string.p_purchased_themes), REQUEST_PURCHASE, this);
    }

    @Override
    public void purchaseCompleted(boolean success, final String sku) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (getString(R.string.sku_themes).equals(sku)) {
                    showLEDColorPicker();
                } else {
                    Timber.d("Unhandled sku: %s", sku);
                }
            }
        });
    }

    private void showLEDColorPicker() {
        newNativeThemePickerDialog(ThemePickerDialog.ColorPalette.LED)
                .show(getFragmentManager(), FRAG_TAG_LED_PICKER);
    }

    private void updateLEDColor() {
        int index = preferences.getInt(R.string.p_led_color);
        LEDColor ledColor = themeCache.getLEDColor(index);
        findPreference(getString(R.string.p_led_color)).setSummary(ledColor.getName());
    }
}
