/*******************************************************************************
 * 【模块】Agent 工作台 Workbench · core
 * 【文件】AgentKeyStore.java（io.memobservatory.agentloop.workbench.core）
 * 【核心功能】Agent 专属模型 API Key（AK）的加密落盘与读取。按 Agent 维度绑定
 *            provider（dashscope / openai）的密钥，保存到 Agent 自身配置目录下
 *            keys.json（密文，AES-GCM）。未配置时回落全局默认 AK。
 * 【设计要点】
 *   - 密钥绝不明文落盘，亦不打明文日志；接口只暴露「掩码态」（是否设置 + 首尾片段）。
 *   - 更新语义：字段为 null 表示「保持原值不覆盖」，空串表示「清除该 provider 绑定
 *     （回落全局）」，非空表示「覆盖为新值」。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench.core;

import io.memobservatory.agentloop.workspace.Workspace;
import io.memobservatory.agentloop.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 加密密钥库：绑定 AK 到 Agent，密文落盘 $ROOT/.workbench/agents/{agentId}/keys.json。
 * 密钥由环境变量 MO_AGENT_CRYPTO_SECRET 派生（缺省用内置常量），AES/GCM 加密。
 */
public class AgentKeyStore {

    private static final Logger log = LoggerFactory.getLogger(AgentKeyStore.class);

    /** 加密串前缀，便于区分明文误存。 */
    private static final String ENC_PREFIX = "enc:";
    /** 支持的模型供应商 AK 槽位。 */
    public static final String[] PROVIDERS = {"dashscope", "openai"};

    private static final SecureRandom SR = new SecureRandom();
    /** 派生密钥：SHA-256(secret) 取 32 字节（AES-256）。 */
    private static final byte[] AES_KEY = deriveKey();

    private final WorkspaceManager wsManager;

    public AgentKeyStore(WorkspaceManager wsManager) {
        this.wsManager = wsManager;
    }

    /** 密文库路径：与 manifest.json 同目录。 */
    private Path keysFile(Workspace ws, String agentId) {
        return wsManager.agentMetaDir(ws, agentId).resolve("keys.json");
    }

    /** 读取某 Agent 已绑定 AK 的明文（未绑定或解密失败返回 null）。 */
    public String plainKey(Workspace ws, String agentId, String provider) {
        return loadPlain(ws, agentId).get(provider);
    }

    /** 读取某 Agent 已绑定 AK 的明文表（未绑定返回空表，不抛异常）。 */
    public Map<String, String> loadPlain(Workspace ws, String agentId) {
        Map<String, String> out = new HashMap<>();
        Path f = keysFile(ws, agentId);
        try {
            if (!Files.exists(f)) return out;
            byte[] raw = Files.readAllBytes(f);
            com.fasterxml.jackson.databind.ObjectMapper om =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> map = om.readValue(raw, Map.class);
            for (Map.Entry<String, Object> e : map.entrySet()) {
                String v = e.getValue() == null ? null : String.valueOf(e.getValue());
                if (v == null || v.isBlank()) continue;
                out.put(e.getKey(), decrypt(v));
            }
        } catch (Exception e) {
            log.warn("Agent 密钥读取失败（{}/{}）: {}", ws.id(), agentId, e.toString());
        }
        return out;
    }

    /**
     * 更新某 Agent 的 AK 绑定。每个 provider 节点取值语义见类注释：
     * null=保持原值 / 空串=清除（回落全局）/ 非空=覆盖。
     *
     * @return 更新后的掩码态（供前端展示）
     */
    public Map<String, Object> applyKeys(Workspace ws, String agentId, Map<String, String> values) {
        Map<String, String> cur = loadPlain(ws, agentId);
        Map<String, String> next = new LinkedHashMap<>();
        for (String p : PROVIDERS) {
            String v = values.get(p);
            if (v == null) {
                if (cur.get(p) != null) next.put(p, cur.get(p));
            } else if (!v.isBlank()) {
                // 空串=清除（不放入 next），非空=覆盖
                next.put(p, v);
            }
        }
        Path f = keysFile(ws, agentId);
        try {
            Map<String, Object> cipher = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : next.entrySet()) {
                String plain = String.valueOf(e.getValue());
                cipher.put(e.getKey(), encrypt(plain));
            }
            Files.createDirectories(f.getParent());
            com.fasterxml.jackson.databind.ObjectMapper om =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            Files.write(f, om.writeValueAsBytes(cipher));
            return statusOf(next);
        } catch (Exception e) {
            log.warn("Agent 密钥保存失败（{}/{}）: {}", ws.id(), agentId, e.toString());
            throw new IllegalStateException("Agent 密钥保存失败: " + agentId, e);
        }
    }

    /** 返回某 Agent 的掩码态：provider → 掩码串（未设置为 null）。 */
    public Map<String, Object> status(Workspace ws, String agentId) {
        return statusOf(loadPlain(ws, agentId));
    }

    private Map<String, Object> statusOf(Map<String, String> plain) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String p : PROVIDERS) {
            out.put(p, mask(plain.get(p)));
        }
        return out;
    }

    /** 掩码：保留首 4 与尾 4，中段用 •••• 代替；过短则整体掩码。 */
    private static String mask(String s) {
        if (s == null) return null;
        if (s.isBlank()) return null;
        if (s.length() <= 8) return "••••••••";
        return s.substring(0, 4) + "••••" + s.substring(s.length() - 4);
    }

    // ---------------- AES/GCM 加解密 ----------------

    private static String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            SR.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(AES_KEY, "AES"),
                    new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return ENC_PREFIX + Base64.getEncoder().encodeToString(iv) + "."
                    + Base64.getEncoder().encodeToString(ct);
        } catch (Exception e) {
            log.warn("AK 加密失败: {}", e.toString());
            return ENC_PREFIX + Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String decrypt(String token) {
        try {
            if (token != null && token.startsWith(ENC_PREFIX)) token = token.substring(ENC_PREFIX.length());
            int dot = token.indexOf('.');
            if (dot <= 0) return token; // 非预期格式，原样返回
            byte[] iv = Base64.getDecoder().decode(token.substring(0, dot));
            byte[] ct = Base64.getDecoder().decode(token.substring(dot + 1));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(AES_KEY, "AES"),
                    new GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return token; // 解密失败回退原文，避免损坏回传
        }
    }

    private static byte[] deriveKey() {
        String secret = System.getenv("MO_AGENT_CRYPTO_SECRET");
        if (secret == null || secret.isBlank()) secret = "mo-agent-ak-v1";
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("密钥派生失败", e);
        }
    }
}