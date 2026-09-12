package com.example.agent.tools.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * SSRF 防护：用户可控 URL 在发起真实请求前的安全校验。
 *
 * 规则：
 *   1. 仅允许绝对 http/https URL（必须有 host）；
 *   2. DNS 解析出的全部地址逐一判定，命中任一保留/内网范围即拒绝；
 *   3. 覆盖 loopback、anyLocal、linkLocal（含云元数据 169.254.169.254）、
 *      siteLocal（10/8、172.16/12、192.168/16）、CGNAT 100.64/10、
 *      IPv6 唯一本地 fc00::/7、链路本地 fe80::/10、多播地址；
 *   4. DNS 解析失败 fail-closed（不允许借解析失败绕过）。
 *
 * 已知残留风险：DNS rebinding（校验与实际连接间的 TOCTOU）与代理环境差异，
 * 本期不做 IP 直连绑定，重定向由 {@link SafeHttpClient} 逐跳重新校验。
 */
@Component
public class UrlSafetyGuard {

    private static final Logger log = LoggerFactory.getLogger(UrlSafetyGuard.class);

    /** 校验通过，返回规范化 URI */
    public URI validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new UnsafeUrlException("URL 不能为空");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new UnsafeUrlException("URL 格式不合法");
        }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new UnsafeUrlException("仅支持绝对 http/https 网址（需包含域名或 IP）");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new UnsafeUrlException("仅支持 http/https 协议");
        }
        if (uri.getRawUserInfo() != null) {
            throw new UnsafeUrlException("网址中不允许包含用户信息（userInfo）");
        }

        String host = uri.getHost();
        // URI.getHost() 对 IPv6 字面量返回带方括号形式（[::1]），DNS 解析前去掉
        String lookupHost = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(lookupHost);
        } catch (UnknownHostException e) {
            log.warn("[SSRF] 域名解析失败，fail-closed 拒绝: host={}", host);
            throw new UnsafeUrlException("目标域名无法解析，已阻止访问");
        }
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                log.warn("[SSRF] 目标地址命中保留/内网范围，拒绝: host={}", host);
                throw new UnsafeUrlException("出于安全限制，不允许访问内网或保留地址");
            }
        }
        return uri;
    }

    /** 判定一个 IP 地址是否属于禁止访问的保留/内网范围（包可见便于单测） */
    static boolean isBlockedAddress(InetAddress address) {
        if (address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            // CGNAT 100.64.0.0/10（isSiteLocalAddress 不覆盖该段）
            if (b0 == 100 && (b1 & 0xC0) == 64) {
                return true;
            }
            // 0.0.0.0/8 "本网络" 保留段（0.0.0.0 本身已被 isAnyLocalAddress 覆盖）
            if (b0 == 0) {
                return true;
            }
            // 受限广播地址
            if (b0 == 255 && b1 == 255 && (bytes[2] & 0xFF) == 255 && (bytes[3] & 0xFF) == 255) {
                return true;
            }
            return false;
        }
        if (address instanceof Inet6Address) {
            int first = bytes[0] & 0xFF;
            // 唯一本地地址 fc00::/7
            if ((first & 0xFE) == 0xFC) {
                return true;
            }
            // IPv4-mapped（::ffff:a.b.c.d）与已弃用的 IPv4-compatible（::a.b.c.d，含 ::127.0.0.1）：
            // 前 10 字节为 0 时统一解包末 4 字节按 IPv4 规则判定（::/::1 已在上方 JDK 判定覆盖）
            if (hasZeroPrefix10(bytes)) {
                byte[] v4 = new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]};
                try {
                    return isBlockedAddress(Inet4Address.getByAddress(v4));
                } catch (UnknownHostException e) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 前 10 字节全为 0（覆盖 ::a.b.c.d 与 ::ffff:a.b.c.d 两类内嵌 IPv4 形式） */
    private static boolean hasZeroPrefix10(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }
}
