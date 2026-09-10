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
 * 密钥由环境变量 MO_AGENT_CRYPTO_SECRET 派生；未设置时首次启动生成随机密钥并持久化到
 * ~/.workbench/.crypto-secret（0600），不使用任何硬编码常量。AES/GCM 加密。
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

    /** 掩码：保留首 4 与尾 4，中段用 •••• 代替；过短则整体掩码。（包内可见，供 GlobalKeyStore 复用） */
    static String mask(String s) {
        if (s == null) return null;
        if (s.isBlank()) return null;
        if (s.length() <= 8) return "••••••••";
        return s.substring(0, 4) + "••••" + s.substring(s.length() - 4);
    }

    // ---------------- AES/GCM 加解密（包内可见，供 GlobalKeyStore 复用） ----------------

    static String encrypt(String plain) {
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
            // 绝不降级为明文（含 base64）落盘：直接失败，让上层报错
            throw new IllegalStateException("AK 加密失败，已阻止明文落盘", e);
        }
    }

    /**
     * 解密。三种情况：
     * - 无 enc: 前缀（历史明文存储）→ 原样返回；
     * - enc: 前缀且认证成功 → 返回明文；
     * - enc: 前缀但解密失败（密钥轮换/损坏，GCM 认证不过）→ 返回 null，
     *   调用方据此回落全局默认 AK，避免把密文误当 key 使用。
     */
    static String decrypt(String token) {
        if (token == null) return null;
        if (!token.startsWith(ENC_PREFIX)) return token; // 历史明文
        try {
            String body = token.substring(ENC_PREFIX.length());
            int dot = body.indexOf('.');
            if (dot <= 0) return null;
            byte[] iv = Base64.getDecoder().decode(body.substring(0, dot));
            byte[] ct = Base64.getDecoder().decode(body.substring(dot + 1));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(AES_KEY, "AES"),
                    new GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Agent AK 密文解密失败（密钥已轮换或文件损坏），该绑定将回落全局默认 AK；"
                    + "如需继续使用专属 key，请在工作台重新绑定。");
            return null;
        }
    }

    /**
     * 派生 AES 密钥，优先级：
     * 1. 环境变量 MO_AGENT_CRYPTO_SECRET（生产推荐，显式可控）；
     * 2. 首次启动随机生成 32 字节密钥，持久化到 ~/.workbench/.crypto-secret（权限 0600），
     *    容器重建后仍可解密已落盘的 AK（该目录随宿主机家目录挂载持久化）；
     * 3. 持久化失败（如只读文件系统）时退化为「每次启动随机」的临时密钥：服务可用，
     *    但重启后历史密文无法解密，会回落全局 AK——仅降级，不使用任何硬编码常量。
     */
    private static byte[] deriveKey() {
        String secret = System.getenv("MO_AGENT_CRYPTO_SECRET");
        if (secret != null && !secret.isBlank()) {
            return sha256(secret);
        }
        try {
            Path dir = Path.of(System.getProperty("user.home", "."), ".workbench");
            Files.createDirectories(dir);
            Path secretFile = dir.resolve(".crypto-secret");
            if (Files.exists(secretFile)) {
                String saved = Files.readString(secretFile, StandardCharsets.UTF_8).trim();
                if (!saved.isBlank()) return sha256(saved);
            }
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            Files.writeString(secretFile, generated, StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(secretFile,
                        java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignore) {
                // 非 POSIX 文件系统（如 Windows）无法设置权限位，跳过
            }
            log.warn("[AgentKeyStore] 未设置 MO_AGENT_CRYPTO_SECRET，已在 {} 生成随机密钥（权限 0600）；"
                    + "生产环境建议显式设置 MO_AGENT_CRYPTO_SECRET", secretFile);
            return sha256(generated);
        } catch (Exception e) {
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            log.warn("[AgentKeyStore] 无法持久化加密密钥（{}），本次启动使用临时随机密钥；"
                    + "重启后已保存的 Agent AK 密文将无法解密，请设置 MO_AGENT_CRYPTO_SECRET", e.toString());
            return sha256(Base64.getUrlEncoder().withoutPadding().encodeToString(raw));
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("密钥派生失败", e);
        }
    }
}