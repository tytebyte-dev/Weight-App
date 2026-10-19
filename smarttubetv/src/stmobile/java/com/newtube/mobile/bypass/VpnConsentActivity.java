package com.newtube.mobile.bypass;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;

import androidx.annotation.Nullable;

/**
 * Shows Android's one-time "Connection request" dialog for a VPN and, once accepted, starts the
 * tunnel. Transparent: it draws nothing of its own and finishes straight after the answer.
 */
public class VpnConsentActivity extends Activity {
    private static final int REQUEST_VPN = 41;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            return; // the dialog is already up; its result arrives in onActivityResult
        }
        Intent consent;
        try {
            consent = VpnService.prepare(this);
        } catch (RuntimeException e) {
            BypassController.instance(this).onVpnConsentResult(this, false);
            finish();
            return;
        }
        if (consent == null) { // already granted
            BypassController.instance(this).onVpnConsentResult(this, true);
            finish();
            return;
        }
        try {
            startActivityForResult(consent, REQUEST_VPN);
        } catch (RuntimeException e) { // no VPN dialog on this device (some TV boxes)
            BypassController.instance(this).onVpnConsentResult(this, false);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN) {
            BypassController.instance(this).onVpnConsentResult(this, resultCode == RESULT_OK);
        }
        finish();
    }
}
