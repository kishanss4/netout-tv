package com.netout.tv;

import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Simple host-based ad / popup / tracker blocker. */
public final class AdBlocker {

    private static final Set<String> HOSTS = new HashSet<>(Arrays.asList(
            // Google / big ad networks
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adservice.google.com", "pagead2.googlesyndication.com", "2mdn.net",
            "google-analytics.com", "googletagservices.com", "amazon-adsystem.com",
            "adnxs.com", "adsrvr.org", "pubmatic.com", "rubiconproject.com",
            "openx.net", "casalemedia.com", "smartadserver.com", "criteo.com",
            "criteo.net", "taboola.com", "outbrain.com", "revcontent.com",
            "mgid.com", "adskeeper.com", "adskeeper.co.uk", "yllix.com",
            "moatads.com", "scorecardresearch.com", "quantserve.com",
            // Popup / pop-under / push networks
            "popads.net", "popcash.net", "propellerads.com", "onclickads.com",
            "onclasrv.com", "onclkds.com", "exoclick.com", "exosrv.com",
            "juicyads.com", "adsterra.com", "highperformanceformat.com",
            "profitablecpmrate.com", "profitablecpmgate.com", "clickadu.com",
            "hilltopads.net", "hilltopads.com", "trafficjunky.com",
            "trafficstars.com", "tsyndicate.com", "realsrv.com", "adcash.com",
            "a-ads.com", "ad-maven.com", "admaven.com", "bidvertiser.com",
            "monetag.com", "pushame.com", "pushwhy.com", "clickaine.com",
            "adexchangeclear.com", "ero-advertising.com", "ads.exoclick.com",
            "adspyglass.com", "adsco.re", "acscdn.com", "dolohen.com",
            "betterstat.com", "histats.com", "popunderjs.com", "cpmstar.com",
            "ad-score.com", "disqusads.com", "mc.yandex.ru", "an.yandex.ru",
            "bebi.com", "wpadmngr.com", "ntvpforever.com", "pubfuture.com"
    ));

    private static final String[] URL_PARTS = {
            "/pagead/", "adsbygoogle", "/adserver", "/ads/show", "popunder",
            "pop-under", "/banner_ads/"
    };

    private AdBlocker() {}

    public static boolean isBlockedHost(String host) {
        if (host == null) return false;
        host = host.toLowerCase();
        while (true) {
            if (HOSTS.contains(host)) return true;
            int dot = host.indexOf('.');
            if (dot < 0 || host.indexOf('.', dot + 1) < 0) return false;
            host = host.substring(dot + 1);
        }
    }

    public static boolean isBlockedUrl(String url, String host) {
        if (isBlockedHost(host)) return true;
        String u = url.toLowerCase();
        for (String p : URL_PARTS) if (u.contains(p)) return true;
        return false;
    }

    public static WebResourceResponse empty() {
        return new WebResourceResponse("text/plain", "utf-8",
                new ByteArrayInputStream(new byte[0]));
    }
}
