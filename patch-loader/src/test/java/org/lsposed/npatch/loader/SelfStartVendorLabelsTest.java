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
    @Test public void huawei() {
        assertEquals("华为 HMS 推送", SelfStartVendorLabels.labelFor("com.huawei.hms.support.api.push.PushReceiver"));
    }
    @Test public void vivo() {
        assertEquals("vivo 推送", SelfStartVendorLabels.labelFor("com.vivo.push.sdk.service.CommandClientService"));
    }
    @Test public void tencent() {
        assertEquals("腾讯信鸽/TPNS", SelfStartVendorLabels.labelFor("com.tencent.android.tpush.XGPushReceiver"));
    }
    @Test public void unknownReturnsEmpty() {
        assertEquals("", SelfStartVendorLabels.labelFor("com.evil.app.BootReceiver"));
        assertEquals("", SelfStartVendorLabels.labelFor(null));
    }
}
