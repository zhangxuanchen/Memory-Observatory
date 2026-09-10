/*******************************************************************************
 * 【模块】Agent 工作台 Workbench · core
 * 【文件】GlobalKeyStore.java（io.memobservatory.agentloop.workbench.core）
 * 【核心功能】全局默认模型 API Key（AK）的加密落盘与读取。工作区侧边栏「模型密钥」
 *            界面配置的全局 AK 保存到 ~/.workbench/model-keys.json（密文，AES-GCM），
 *            供所有未单独绑定 AK 的 Agent（含内置工作区经理）与 .log 大模型格式化使用。
 * 【设计要点】
 *   - 取钥优先级：界面配置（本库）→ 环境变量 → agent.env 兜底文件。界面保存是显式
 *     最新操作，立即生效且优先于启动期环境变量。
 *   - 复用 AgentKeyStore 的 AES/GCM 加解密与掩码规则：密钥绝不明文落盘，不打明文日志，
 *     接口只暴露「掩码态」（是否设置 + 首尾片段）。
 *   - 文件随 ~/.workbench（宿主机家目录挂载）持久化，容器重建后仍有效。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局默认模型 AK 密钥库：界面配置密文落盘 ~/.workbench/model-keys.json。
 * 纯静态工具（与 AgentProperties.FILE_ENV 同风格），供 ModelFactory / AgentFactory /
 * WriteController / AgentKeyController 直接取用，避免侵入各处构造器签名。
 */
public final class GlobalKeyStore {

    private static final Logger log = LoggerFactory.getLogger(GlobalKeyStore.class);

    /** 密文库路径：~/.workbench/model-keys.json（与 .crypto-secret 同目录，持久化）。 */
    private static final Path STORE_FILE =
            Path.of(System.getProperty("user.home", "."), ".workbench", "model-keys.json");

    private GlobalKeyStore() {
    }

    /** 掩码桥接（复用 AgentKeyStore 规则），供其他包的状态端点展示。 */
    public static String mask(String s) {
        return AgentKeyStore.mask(s);
    }

    /** 读取某 provider 界面配置的全局 AK 明文（未配置返回 null，不抛异常）。 */
    public static String plain(String provider) {
        return loadPlain().get(provider);
    }

    /** 读取全部界面配置 AK 的明文表（未配置返回空表，不抛异常）。 */
    public static Map<String, String> loadPlain() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            if (!Files.exists(STORE_FILE)) return out;
            byte[] raw = Files.readAllBytes(STORE_FILE);
            com.fasterxml.jackson.databind.ObjectMapper om =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> map = om.readValue(raw, Map.class);
            for (Map.Entry<String, Object> e : map.entrySet()) {
                String v = e.getValue() == null ? null : String.valueOf(e.getValue());
                if (v == null || v.isBlank()) continue;
                String plain = AgentKeyStore.decrypt(v);
                if (plain != null && !plain.isBlank()) out.put(e.getKey(), plain);
            }
        } catch (Exception e) {
            log.warn("全局模型密钥读取失败（{}）: {}", STORE_FILE, e.toString());
        }
        return out;
    }

    /**
     * 保存/清除某 provider 的全局 AK。apiKey 非空=覆盖保存（加密落盘）；空=清除（回落环境变量）。
     *
     * @return 更新后的掩码态（provider → {masked, source}，供前端展示）
     */
    public static Map<String, Object> apply(String provider, String apiKey) {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider 不能为空");
        }
        Map<String, String> cur = loadPlain();
        try {
            com.fasterxml.jackson.databind.ObjectMapper om =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> cipher = new LinkedHashMap<>();
            if (apiKey != null && !apiKey.isBlank()) {
                cipher.put(provider, AgentKeyStore.encrypt(apiKey.trim()));
                for (Map.Entry<String, String> e : cur.entrySet()) {
                    if (!e.getKey().equals(provider)) {
                        cipher.put(e.getKey(), AgentKeyStore.encrypt(e.getValue()));
                    }
                }
            } else {
                // 清除：保留其他 provider 原值
                for (Map.Entry<String, String> e : cur.entrySet()) {
                    if (!e.getKey().equals(provider)) {
                        cipher.put(e.getKey(), AgentKeyStore.encrypt(e.getValue()));
                    }
                }
            }
            Files.createDirectories(STORE_FILE.getParent());
            Files.write(STORE_FILE, om.writeValueAsBytes(cipher));
            try {
                Files.setPosixFilePermissions(STORE_FILE,
                        java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignore) {
                // 非 POSIX 文件系统（如 Windows）无法设置权限位，跳过
            }
        } catch (Exception e) {
            log.warn("全局模型密钥保存失败: {}", e.toString());
            throw new IllegalStateException("全局模型密钥保存失败", e);
        }
        return status();
    }

    /** 全局 AK 掩码态：provider → {masked: 掩码串|null, source: "ui"|null}。 */
    public static Map<String, Object> status() {
        Map<String, String> plain = loadPlain();
        Map<String, Object> out = new LinkedHashMap<>();
        for (String p : AgentKeyStore.PROVIDERS) {
            String v = plain.get(p);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("masked", AgentKeyStore.mask(v));
            node.put("source", (v != null && !v.isBlank()) ? "ui" : null);
            out.put(p, node);
        }
        return out;
    }
}
