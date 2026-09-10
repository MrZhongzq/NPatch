package org.lsposed.npatch.share;

/** Heuristic vendor/SDK label for a broadcast receiver, by package/class-name prefix. UI hint only. */
public final class SelfStartVendorLabels {
    private SelfStartVendorLabels() {}

    private static final String[][] TABLE = {
            {"com.xiaomi.mipush", "小米推送 (MiPush)"},
            {"com.xiaomi.push", "小米推送 (MiPush)"},
            {"com.xiaomi.", "小米推送 (MiPush)"},
            {"com.google.firebase", "Google FCM/GCM"},
            {"com.google.android.gms", "Google FCM/GCM"},
            {"com.google.android.c2dm", "Google FCM/GCM"},
            {"com.huawei.hms", "华为 HMS 推送"},
            {"com.huawei.android.push", "华为 HMS 推送"},
            {"com.vivo.push", "vivo 推送"},
            {"com.heytap", "OPPO/ColorOS 推送"},
            {"com.coloros", "OPPO/ColorOS 推送"},
            {"com.oppo", "OPPO/ColorOS 推送"},
            {"com.meizu.cloud.pushsdk", "魅族 Flyme 推送"},
            {"com.meizu", "魅族 Flyme 推送"},
            {"cn.jpush", "极光 JPush"},
            {"cn.jiguang", "极光 JPush"},
            {"com.igexin", "个推 GeTui"},
            {"com.getui", "个推 GeTui"},
            {"com.tencent.android.tpush", "腾讯信鸽/TPNS"},
            {"com.tencent.tpns", "腾讯信鸽/TPNS"},
            {"org.android.agoo", "阿里 ACCS/agoo"},
            {"com.taobao.accs", "阿里 ACCS/agoo"},
    };

    /** @return friendly vendor label, or "" if none matches (or class is null). */
    public static String labelFor(String receiverClassName) {
        if (receiverClassName == null) return "";
        for (String[] row : TABLE) {
            if (receiverClassName.startsWith(row[0])) return row[1];
        }
        return "";
    }
}
