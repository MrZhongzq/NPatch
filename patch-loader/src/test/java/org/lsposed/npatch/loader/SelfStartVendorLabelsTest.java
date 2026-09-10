package org.lsposed.npatch.loader;

import static org.junit.Assert.*;

import org.junit.Test;
import org.lsposed.npatch.share.SelfStartVendorLabels;

public class SelfStartVendorLabelsTest {
    @Test public void mipush() {
        assertEquals("小米推送 (MiPush)", SelfStartVendorLabels.labelFor("com.xiaomi.mipush.sdk.PushMessageHandler"));
    }
    @Test public void fcm() {
        assertEquals("Google FCM/GCM", SelfStartVendorLabels.labelFor("com.google.firebase.iid.FirebaseInstanceIdReceiver"));
    }
    @Test public void jpush() {
        assertEquals("极光 JPush", SelfStartVendorLabels.labelFor("cn.jpush.android.service.PushReceiver"));
    }
    @Test public void unknownReturnsEmpty() {
        assertEquals("", SelfStartVendorLabels.labelFor("com.evil.app.BootReceiver"));
        assertEquals("", SelfStartVendorLabels.labelFor(null));
    }
}
