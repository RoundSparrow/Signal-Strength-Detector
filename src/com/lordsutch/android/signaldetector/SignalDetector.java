package com.lordsutch.android.signaldetector;

// Android Packages

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.lordsutch.android.signaldetector.SignalDetectorService.LocalBinder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class SignalDetector extends AppCompatActivity {
    public static final String TAG = SignalDetector.class.getSimpleName();

    public static WebView leafletView = null;
//    public static MapView mapView = null;
    private TelephonyManager mTelephonyManager = null;
    private String baseLayer = "shields";
    private String coverageLayer = "provider";
    private BroadcastReceiver receiver;

    /**
     * Called when the activity is first created.
     */
    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // getWindow().requestFeature(Window.FEATURE_PROGRESS);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.main);
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        mTelephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);

/*
        mapView = (MapView) findViewById(R.id.2mapview);
        mapView.setZoom(14);

        UserLocationOverlay userLocationOverlay = new UserLocationOverlay(new GpsLocationProvider(this),
                mapView);
        userLocationOverlay.enableMyLocation();
        userLocationOverlay.setDrawAccuracyEnabled(true);
        mapView.getOverlays().add(userLocationOverlay);
*/

        leafletView = (WebView) findViewById(R.id.leafletView);

        WebSettings webSettings = leafletView.getSettings();
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setJavaScriptEnabled(true);
        webSettings.setLoadsImagesAutomatically(true);

        final Activity activity = this;

        leafletView.setWebChromeClient(new WebChromeClient() {
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Toast.makeText(activity, description, Toast.LENGTH_SHORT).show();
            }

            public boolean onConsoleMessage(ConsoleMessage cm) {
                Log.d(TAG, cm.message() + " -- From line "
                        + cm.lineNumber() + " of "
                        + cm.sourceId());
                return true;
            }

            // Enable client caching
            @Override
            public void onReachedMaxAppCacheSize(long spaceNeeded, long totalUsedQuota,
                                                 WebStorage.QuotaUpdater quotaUpdater) {
                quotaUpdater.updateQuota(spaceNeeded * 2);
            }
        });

        webSettings.setDomStorageEnabled(true);

    	/*
        This next one is crazy. It's the DEFAULT location for your app's cache
    	But it didn't work for me without this line.
    	UPDATE: no hardcoded path. Thanks to Kevin Hawkins */
        String appCachePath = getApplicationContext().getCacheDir().getAbsolutePath();
        webSettings.setAppCachePath(appCachePath);
        webSettings.setAppCacheEnabled(true);
        webSettings.setBuiltInZoomControls(false);
        webSettings.setAllowFileAccess(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_DENIED ||
                    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED)) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 0);
        }

        leafletView.loadUrl("file:///android_asset/leaflet.html");
        reloadPreferences();

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                signalInfo s = intent.getParcelableExtra(SignalDetectorService.SD_MESSAGE);
                updateSigInfo(s);
            }
        };

    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String permissions[], @NonNull int[] grantResults) {
        if (requestCode == 0) {
            if (mService != null && grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                mService.startGPS();
            }
        }
    }


    private SignalDetectorService mService = null;
    private boolean mBound = false;

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.mainmenu, menu);

        return true;
    }

    // Define a DialogFragment that displays the error dialog
    public static class ErrorDialogFragment extends DialogFragment {
        // Global field to contain the error dialog
        private Dialog mDialog;

        // Default constructor. Sets the dialog field to null
        public ErrorDialogFragment() {
            super();
            mDialog = null;
        }

        // Set the dialog to display
        public void setDialog(Dialog dialog) {
            mDialog = dialog;
        }

        // Return a Dialog to the DialogFragment.
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            return mDialog;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        bindSDService();
        LocalBroadcastManager.getInstance(this).registerReceiver((receiver),
                new IntentFilter(SignalDetectorService.SD_RESULT)
        );
    }

    @Override
    protected void onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver);
        super.onStop();
    }

    private void bindSDService() {
        // Bind cell tracking service
        Intent intent = new Intent(this, SignalDetectorService.class);

        startService(intent);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        mBound = true;
    }

    private void unbindSDService() {
        if (mBound) {
            unbindService(mConnection);
            mBound = false;
        }
        Intent intent = new Intent(this, SignalDetectorService.class);
        stopService(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();

//        Log.d(TAG, "Resuming");
        // leafletView.reload();
        if (mSignalInfo != null)
            updateGui();
    }

    private void enableLocationSettings() {
        Intent settingsIntent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        startActivity(settingsIntent);
    }

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            LocalBinder binder = (LocalBinder) service;
            mService = binder.getService();
            mBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mBound = false;
        }
    };

    private boolean validLTESignalStrength(int strength) {
        return (strength > -200 && strength < 0);
    }

    private boolean validRSSISignalStrength(int strength) {
        return (strength > -120 && strength < 0);
    }

    private boolean validCellID(int eci) {
        return (eci >= 0 && eci <= 0x0FFFFFFF);
    }

    private double bslat = 999;
    private double bslon = 999;

    private signalInfo mSignalInfo = null;

    double speedfactor = 3.6;
    String speedlabel = "km/h";

    double accuracyfactor = 1.0;
    String accuracylabel = "m";

    double bearing = 0.0;

    // Swiped from https://en.wikipedia.org/wiki/Mobile_country_code
    private List<Integer> threeDigitMNCList = Arrays.asList(
            365, // Anguilla
            344, // Antigua and Barbuda
            722, // Argentina
            342, // Barbados
            348, // British Virgin Islands
            302, // Canada
            346, // Cayman Islands
            732, // Columbia
            366, // Dominica
            750, // Falkland Islands
            352, // Grenada
            708, // Honduras
            // India seems to be a mix of 2 and 3 digits?
            338, // Jamaica
            // Malaysia has several 3 digit codes, all >= 100
            334, // Mexico
            354, // Montserrat
            330, // Puerto Rico mostly has 3-digit codes over 100
            356, // Saint Kitts and Nevis
            358, // Saint Lucia
            360, // Saint Vincent and the Grenadines
            376, // Turks and Caicos Islands
            310, 311, 312, 313, 316 // USA; Guam
    );

    private boolean is3digitMnc(int mcc) {
        return threeDigitMNCList.contains(mcc);
    }

    private String formatMccMnc(int mcc, int mnc) {
        if(mnc >= 100 || is3digitMnc(mcc)) {
            return String.format(Locale.US, "%03d-%03d", mcc, mnc);
        } else {
            return String.format(Locale.US, "%03d-%02d", mcc, mnc);
        }
    }

    private String directionForBearing(double bearing) {
        if (bearing > 0) {
            int index = (int) Math.ceil((bearing + 11.25) / 22.5);

            int dir[] = {0, R.string.bearing_north, R.string.bearing_nne, R.string.bearing_northeast,
                    R.string.bearing_ene, R.string.bearing_east, R.string.bearing_ese, R.string.bearing_southeast,
                    R.string.bearing_sse, R.string.bearing_south, R.string.bearing_ssw, R.string.bearing_southwest,
                    R.string.bearing_wsw, R.string.bearing_west, R.string.bearing_wnw, R.string.bearing_northwest,
                    R.string.bearing_nnw, R.string.bearing_north};

            return getResources().getString(dir[index]);
        } else {
            return "";
        }
    }

    private boolean validPhysicalCellID(int pci) {
        return (pci >= 0 && pci <= 503);
    }

    private boolean tradunits = false;
    private boolean bsmarker = false;
    private boolean taAsDistance = false;
    private String ta_distance_units = "mi";

    public void updateSigInfo(signalInfo signal) {
        mSignalInfo = signal;
        if(this.hasWindowFocus())
            updateGui();
    }

    /* Speed of light in air at sea level is approx. 299,700 km/s according to Wikipedia
     * Android timing advance is in microseconds according to:
     * https://android.googlesource.com/platform/hardware/ril/+/master/include/telephony/ril.h
     * ... but empirically I don't think this is correct.
     * I think it's probably 16 Ts = 16/(15000 * 2048) s, which makes the distance equivalent
     * to 78.12 m or 0.0485 mi (http://niviuk.free.fr/store_lte.php)
     *
     * Might be 229.7/2 = 159.85 m; try that...
     */
    private double timingAdvanceToMeters(int timingAdvance) {
        return timingAdvance * 159.85;
    }

    private double timingAdvanceToDistance(int timingAdvance) {
        return timingAdvanceToMeters(timingAdvance) / (tradunits ? 1609.334 : 1000);
    }

    private String formatTimingAdvance(int timingAdvance) {
        if(taAsDistance) {
            return String.format(Locale.getDefault(), "\u00a0TA=%.1f\u202f%s",
                    timingAdvanceToDistance(timingAdvance), ta_distance_units);
        } else {
            // 16 Ts = 25/48 µs
            return String.format(Locale.getDefault(), "\u00a0TA=%.0f\u202fµs", timingAdvance*25/48.0);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if(hasFocus)
            updateGui();
    }

    private void updateGui() {
        if(mSignalInfo == null)
            return;

        bslat = mSignalInfo.bslat;
        bslon = mSignalInfo.bslon;

        if (mSignalInfo.bearing > 0.0)
            bearing = mSignalInfo.bearing;

        TextView latlon = (TextView) findViewById(R.id.positionLatLon);

        latlon.setText(String.format(Locale.getDefault(), "%3.5f\u00b0%s %3.5f\u00b0%s (\u00b1%.0f\u202f%s)",
                Math.abs(mSignalInfo.latitude), getResources().getString(mSignalInfo.latitude >= 0 ? R.string.bearing_north : R.string.bearing_south),
                Math.abs(mSignalInfo.longitude), getResources().getString(mSignalInfo.longitude >= 0 ? R.string.bearing_east : R.string.bearing_west),
                mSignalInfo.accuracy * accuracyfactor, accuracylabel));

        TextView speed = (TextView) findViewById(R.id.speed);

        if (bearing > 0.0)
            speed.setText(String.format(Locale.getDefault(), "%3.1f %s %s", mSignalInfo.speed * speedfactor, speedlabel,
                    directionForBearing(bearing)));
        else
            speed.setText(String.format(Locale.getDefault(), "%3.1f %s", mSignalInfo.speed * speedfactor, speedlabel));

        TextView servingid = (TextView) findViewById(R.id.cellid);
        TextView bsLabel = (TextView) findViewById(R.id.bsLabel);
        TextView cdmaBS = (TextView) findViewById(R.id.cdma_sysinfo);
        TextView cdmaStrength = (TextView) findViewById(R.id.cdmaSigStrength);
        TextView otherSites = (TextView) findViewById(R.id.otherLteSites);

        LinearLayout voiceSignalBlock = (LinearLayout) findViewById(R.id.voiceSignalBlock);
        LinearLayout lteBlock = (LinearLayout) findViewById(R.id.lteBlock);
        LinearLayout lteOtherBlock = (LinearLayout) findViewById(R.id.lteOtherBlock);
        LinearLayout preLteBlock = (LinearLayout) findViewById(R.id.preLteBlock);

        if (mSignalInfo.networkType == TelephonyManager.NETWORK_TYPE_LTE) {
            ArrayList<String> cellIds = new ArrayList<>();

            if (validTAC(mSignalInfo.tac))
                cellIds.add(String.format("TAC\u00a0%04X", mSignalInfo.tac));

            if (validCellID(mSignalInfo.gci))
                cellIds.add(String.format("GCI\u00a0%08X", mSignalInfo.gci));

            if (validPhysicalCellID(mSignalInfo.pci))
                cellIds.add(String.format(Locale.getDefault(), "PCI\u00a0%03d", mSignalInfo.pci));

            if (!cellIds.isEmpty()) {
                servingid.setText(TextUtils.join(", ", cellIds));
            } else {
                servingid.setText(R.string.missing);
            }
            lteBlock.setVisibility(View.VISIBLE);
            lteOtherBlock.setVisibility(View.VISIBLE);
        } else {
            servingid.setText(R.string.none);
            lteBlock.setVisibility(View.GONE);
            lteOtherBlock.setVisibility(View.GONE);
        }

        if (mSignalInfo.otherCells != null) {
            ArrayList<String> otherSitesList = new ArrayList<>();

            Collections.sort(mSignalInfo.otherCells, new Comparator<otherLteCell>() {
                @Override
                public int compare(otherLteCell lhs, otherLteCell rhs) {
                    int c1 = rhs.lteSigStrength - lhs.lteSigStrength;
                    if(c1 == 0) { // Fall back to compare PCI
                        return lhs.pci - rhs.pci;
                    }
                    return c1;
                }
            });

            for (otherLteCell otherCell : mSignalInfo.otherCells) {
                if (validPhysicalCellID(otherCell.pci) && validLTESignalStrength(otherCell.lteSigStrength)) {
                    String sigInfo = String.format(Locale.getDefault(), "%d\u202FdBm", otherCell.lteSigStrength);
                    if(mService.validTimingAdvance(otherCell.timingAdvance)) {
                        sigInfo += formatTimingAdvance(otherCell.timingAdvance);
                    }

                    otherSitesList.add(String.format(Locale.getDefault(), "%03d\u00a0(%s)",
                            otherCell.pci, sigInfo));
                }
            }
            if (otherSitesList.isEmpty())
                otherSites.setText(R.string.none);
            else
                otherSites.setText(TextUtils.join("; ", otherSitesList));
        }

        TextView network = (TextView) findViewById(R.id.networkString);

        int voiceSigStrength = Integer.MAX_VALUE;
        boolean voiceDataSame = true;

        if (mSignalInfo.phoneType == TelephonyManager.PHONE_TYPE_CDMA) {
            voiceSigStrength = mSignalInfo.cdmaSigStrength;
        } else if (mSignalInfo.phoneType == TelephonyManager.PHONE_TYPE_GSM) {
            voiceSigStrength = mSignalInfo.gsmSigStrength;
        }
        int dataSigStrength = voiceSigStrength;
        boolean lteMode = false;

        switch (mSignalInfo.networkType) {
            case TelephonyManager.NETWORK_TYPE_LTE:
                if (validLTESignalStrength(mSignalInfo.lteSigStrength)) {
//                    getSupportActionBar().setLogo(R.drawable.ic_launcher);
                    voiceDataSame = false;
                    dataSigStrength = mSignalInfo.lteSigStrength;
                    lteMode = true;
//                } else {
//                    getSupportActionBar().setLogo(R.drawable.ic_stat_non4g);
                }
                break;

            case TelephonyManager.NETWORK_TYPE_EHRPD:
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
//                getSupportActionBar().setLogo(R.drawable.ic_stat_non4g);
                if (validRSSISignalStrength(mSignalInfo.evdoSigStrength)) {
                    voiceDataSame = false;
                    dataSigStrength = mSignalInfo.evdoSigStrength;
                }
                break;

            default:
//                getSupportActionBar().setLogo(R.drawable.ic_stat_non4g);
                break;
        }

        String netText = networkString(mSignalInfo.networkType);
        if (lteMode && validMnc(mSignalInfo.mcc) && validMnc(mSignalInfo.mnc)) {
            netText += " " + formatMccMnc(mSignalInfo.mcc, mSignalInfo.mnc);
        }

        if (lteMode && mSignalInfo.lteBand > 0) {
            netText += String.format(Locale.getDefault(), " B%d", mSignalInfo.lteBand);
        }

        if (validLTESignalStrength(dataSigStrength)) {
            netText += String.format(Locale.getDefault(), " %d\u202FdBm", dataSigStrength);
            if(mService.validTimingAdvance(mSignalInfo.timingAdvance))
                netText += formatTimingAdvance(mSignalInfo.timingAdvance);
        }

        if (mSignalInfo.roaming)
            netText += " " + getString(R.string.roamingInd);

        network.setText(netText);

        if (!voiceDataSame && validRSSISignalStrength(voiceSigStrength)) {
            cdmaStrength.setText(String.valueOf(voiceSigStrength) + "\u202FdBm");
            voiceSignalBlock.setVisibility(View.VISIBLE);
        } else {
            voiceSignalBlock.setVisibility(View.GONE);
        }

        ArrayList<String> bsList = new ArrayList<>();

        if (mSignalInfo.sid >= 0 && mSignalInfo.nid >= 0 && mSignalInfo.bsid >= 0 &&
                (mSignalInfo.phoneType == TelephonyManager.PHONE_TYPE_CDMA)) {
            bsLabel.setText(R.string.cdma_1xrtt_base_station);

            bsList.add("SID\u00A0" + mSignalInfo.sid);
            bsList.add("NID\u00A0" + mSignalInfo.nid);
            bsList.add(String.format(Locale.getDefault(), "BSID\u00A0%d\u00A0(x%X)", mSignalInfo.bsid, mSignalInfo.bsid));
        } else if (mSignalInfo.phoneType == TelephonyManager.PHONE_TYPE_GSM) {
            bsLabel.setText(R.string._2g_3g_tower);

            bsList.add("MNC\u00A0" + mSignalInfo.operator);
            if (mSignalInfo.lac > 0)
                bsList.add("LAC\u00A0" + String.valueOf(mSignalInfo.lac));

            if (mSignalInfo.rnc > 0 && mSignalInfo.rnc != mSignalInfo.lac)
                bsList.add("RNC\u00A0" + String.valueOf(mSignalInfo.rnc));

            if (mSignalInfo.cid > 0)
                bsList.add("CID\u00A0" + String.valueOf(mSignalInfo.cid));

            if (mSignalInfo.psc > 0)
                bsList.add("PSC\u00A0" + String.valueOf(mSignalInfo.psc));
        }

        if (!bsList.isEmpty()) {
            cdmaBS.setText(TextUtils.join(", ", bsList));
            preLteBlock.setVisibility(View.VISIBLE);
        } else {
            cdmaBS.setText(R.string.none);
            preLteBlock.setVisibility(View.GONE);
        }

        if (Math.abs(mSignalInfo.latitude) <= 200)
            centerMap(mSignalInfo.latitude, mSignalInfo.longitude, mSignalInfo.accuracy, mSignalInfo.avgspeed, bearing,
                    mSignalInfo.fixAge);
        addBsMarker();
    }

    private boolean validTAC(int tac) {
        return (tac > 0x0000 && tac < 0xFFFF); // 0, 0xFFFF are reserved values
    }

    private boolean validMnc(int mcc) {
        return (mcc > 0 && mcc <= 999);
    }

    private String networkString(int networkType) {
        switch (networkType) {
            case TelephonyManager.NETWORK_TYPE_EHRPD:
                return "eHRPD";
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
                return "EVDO Rel. 0";
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
                return "EVDO Rev. A";
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
                return "EVDO Rev. B";
            case TelephonyManager.NETWORK_TYPE_GPRS:
                return "GPRS";
            case TelephonyManager.NETWORK_TYPE_EDGE:
                return "EDGE";
            case TelephonyManager.NETWORK_TYPE_UMTS:
                return "UMTS";
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_HSPA:
                return "HSPA";
            case TelephonyManager.NETWORK_TYPE_HSPAP:
                return "HSPA+";
            case TelephonyManager.NETWORK_TYPE_CDMA:
                return "CDMA";
            case TelephonyManager.NETWORK_TYPE_1xRTT:
                return "1xRTT";
            case TelephonyManager.NETWORK_TYPE_IDEN:
                return "iDEN";
            case TelephonyManager.NETWORK_TYPE_LTE:
                return "LTE";
            default:
                return "Unknown";
        }
    }

    private boolean isEVDONetwork(int networkType) {
        return (networkType == TelephonyManager.NETWORK_TYPE_EHRPD ||
                networkType == TelephonyManager.NETWORK_TYPE_EVDO_0 ||
                networkType == TelephonyManager.NETWORK_TYPE_EVDO_A ||
                networkType == TelephonyManager.NETWORK_TYPE_EVDO_B);
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    // Use evaluateJavascript if available (KITKAT+), otherwise hack
    private void execJavascript(String script) {
//        Log.d(TAG, script);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
            leafletView.evaluateJavascript(script, null);
        else
            leafletView.loadUrl("javascript:" + script);
    }

    private int zoomForSpeed(double speed) {
        speed = speed*3.6; // Convert to km/h from m/s

        if(speed >= 83)
            return 13;
        else if (speed >= 63)
            return 14;
        else if (speed >= 43)
            return 15;
        else if (speed >= 23)
            return 16;
        else if (speed >= 5)
            return 17;

        return 0; // Don't zoom
    }

    private void centerMap(double latitude, double longitude, double accuracy, double speed,
                           double bearing, long fixAge) {
        boolean staleFix = fixAge > (30 * 1000); // 30 seconds

        if(coverageLayer.equals("provider")) {
            coverageLayer = mTelephonyManager.getSimOperator();
            if (coverageLayer == null)
                coverageLayer = mTelephonyManager.getNetworkOperator();
            if (coverageLayer == null)
                coverageLayer = "";
        }

        double towerRadius = 0.0;

        if(mSignalInfo != null)
            towerRadius = timingAdvanceToMeters(mSignalInfo.timingAdvance);

/*
        mapView.setCenter(new LatLng(latitude, longitude));
        int zoom = zoomForSpeed(speed);
        if(zoom > 0)
            mapView.setZoom(zoom);
        // TODO Add markers here
*/
        execJavascript(String.format(Locale.US, "recenter(%.5f,%.5f,%f,%.0f,%.0f,%s,\"%s\",\"%s\",%.0f);",
                latitude, longitude, accuracy, speed, bearing, staleFix, coverageLayer, baseLayer,
                towerRadius));
    }

//    private Marker baseMarker = null;

    private void addBsMarker() {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        bsmarker = sharedPref.getBoolean("show_base_station", false);

/*
        LatLng location = new LatLng(bslat, bslon);
        // TODO Place markers
        if(bsmarker && Math.abs(bslat) <= 90 && Math.abs(bslon) <= 190) {
            if(baseMarker == null) {
                baseMarker = new Marker(mapView, "Base Station", "CDMA base station location.",
                        location);
                baseMarker.addTo(mapView);
            } else {
                baseMarker.setPoint(location);
            }
        } else if (baseMarker != null) {
            mapView.removeMarker(baseMarker);
        }
*/

        if (bsmarker && Math.abs(bslat) <= 90 && Math.abs(bslon) <= 190)
            execJavascript(String.format(Locale.US, "placeMarker(%.5f,%.5f);", bslat, bslon));
        else
            execJavascript("clearMarker();");
    }

    private void updateUnits() {
        if (tradunits) {
            speedfactor = 2.237;
            speedlabel = "mph";
            accuracyfactor = 3.28084;
            accuracylabel = "ft";
            ta_distance_units = "mi";
        } else {
            speedfactor = 3.6;
            speedlabel = "km/h";
            accuracyfactor = 1.0;
            accuracylabel = "m";
            ta_distance_units = "km";
        }
    }

    public void launchSettings(MenuItem x) {
        Intent myIntent = new Intent(this, SettingsActivity.class);
        startActivityForResult(myIntent, 0);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String action = intent.getAction();
        if (action != null && action.equals(SignalDetectorService.ACTION_STOP)) {
            Log.d(TAG, "onActivityResult: exit received.");
            unbindSDService();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        unbindSDService();
        reloadPreferences();
        bindSDService();
    }

    private void reloadPreferences() {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        bsmarker = sharedPref.getBoolean("show_base_station", false);
        tradunits = sharedPref.getBoolean("traditional_units", false);
        baseLayer = sharedPref.getString("tile_source", "osm");
        taAsDistance = sharedPref.getBoolean("ta_distance", false);

        setMapView(baseLayer);
        addMapOverlays(sharedPref.getString("overlay_tile_source", "provider"));
        updateUnits();
    }

    public void exitApp(MenuItem x) {
        unbindSDService();
        finish();
    }

    @Override
    protected void onDestroy() {
        if (mBound) {
            unbindService(mConnection);
            mBound = false;
        }
//        System.gc();
        super.onDestroy();
    }

    /**
     * Dialog to prompt users to enable GPS on the device.
     */
    @SuppressLint("ValidFragment")
    public class EnableGpsDialogFragment extends DialogFragment {
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            return new AlertDialog.Builder(getActivity())
                    .setTitle(R.string.enable_gps)
                    .setMessage(R.string.enable_gps_dialog)
                    .setPositiveButton(R.string.enable_gps, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            enableLocationSettings();
                        }
                    })
                    .create();
        }
    }

    protected void addMapOverlays(String layer) {
        String layerName = layer;

        if(layer.equalsIgnoreCase("provider")) {
            layer = mTelephonyManager.getSimOperator();
            if (layer == null)
                layer = mTelephonyManager.getNetworkOperator();
            layerName = mTelephonyManager.getSimOperatorName();
        }

        coverageLayer = layer;
        execJavascript("setOverlayLayer(\""+layer+"\")");
/*
        ITileLayer source = new WebSourceTileLayer("coverage",
                "http://tiles-day.cdn.sensorly.net/tile/any/"+providerFragment+"/{z}/{x}/{x}/{y}/{y}.png?s=256")
                .setName(layerName)
                .setAttribution("© Sensorly")
                .setMinimumZoomLevel(1)
                .setMaximumZoomLevel(18);

//        MapTileLayerBase base = new MapTileLayerBasic(this, source, mapView);
//        Overlay overlay = new TilesOverlay(base);
        mapView.addTileSource(source);
*/
    }

    protected void setMapView(String layer) {
        baseLayer = layer;
        execJavascript("setBaseLayer(\""+layer+"\")");
/*

        ITileLayer source = null;

        if (layer.equalsIgnoreCase("shields")) {
            source = new WebSourceTileLayer("shields",
                    "http://tile.openstreetmap.us/osmus_shields/{z}/{x}/{y}.png")
                    .setName("Shields")
                    .setAttribution("© OpenStreetMap")
                    .setMinimumZoomLevel(1)
                    .setMaximumZoomLevel(18);
        } else if(layer.equalsIgnoreCase("mapquest")) {
            source = new WebSourceTileLayer("mapquest",
                    "http://otile1.mqcdn.com/tiles/1.0.0/map/{z}/{x}/{y}.jpg")
                    .setName("MapQuest Open")
                    .setAttribution("© OpenStreetMap, MapQuest")
                    .setMinimumZoomLevel(1)
                    .setMaximumZoomLevel(18);
        } else if(layer.equalsIgnoreCase("usgs-aerial")) {
            source = new WebSourceTileLayer("usgs-aerial",
                    "http://tile.openstreetmap.us/usgs_large_scale/{z}/{x}/{y}.jpg")
                    .setName("USGS-NAIP")
                    .setAttribution("Courtesy USGS/NAIP")
                    .setMinimumZoomLevel(1)
                    .setMaximumZoomLevel(18);
        } else if(layer.equalsIgnoreCase("topos")) {
            source = new WebSourceTileLayer("topos",
                    "http://tile.openstreetmap.us/usgs_scanned_topos/{z}/{x}/{y}.jpg")
                    .setName("USGS-NAIP")
                    .setAttribution("Courtesy USGS/NAIP")
                    .setMinimumZoomLevel(12)
                    .setMaximumZoomLevel(18);
        }

        if (source != null) {
            mapView.setTileSource(source);
            mapView.setScrollableAreaLimit(source.getBoundingBox());
            mapView.setMinZoomLevel(mapView.getTileProvider().getMinimumZoomLevel());
            mapView.setMaxZoomLevel(mapView.getTileProvider().getMaximumZoomLevel());
//            mapView.setCenter(mapView.getTileProvider().getCenterCoordinate());
//            mapView.setZoom(13);
*/
    }
}

