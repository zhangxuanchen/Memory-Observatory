/*******************************************************************************
 * 【模块】Workspace-Agent · workspace · web
 * 【文件】FsBrowserController.java（io.memobservatory.agentloop.workspace.web）
 * 【核心功能】服务端文件夹浏览器：GET /api/agent/fs/browse 列出某目录下的子目录，
 *            供前端"选择工作区文件夹"弹框逐层导航。自托管场景（浏览器与 server 同机），
 *            用服务端目录树代替浏览器 file input（后者拿不到服务端绝对路径）。
 *            path 为空时从用户主目录开始。
 * 【核心改动】2026-08-23 新增。
 *******************************************************************************/
package io.memobservatory.agentloop.workspace.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端目录浏览端点。
 */
@RestController
@RequestMapping("/api/agent/fs")
public class FsBrowserController {

    /** 列出 path（缺省主目录）下的全部子目录。 */
    @GetMapping("/browse")
    public BrowseView browse(@RequestParam(required = false) String path) {
        Path p = (path == null || path.isBlank())
                ? Path.of(System.getProperty("user.home"))
                : Path.of(path);
        p = p.toAbsolutePath().normalize();
        if (!Files.isDirectory(p)) {
            throw new IllegalArgumentException("路径不是目录: " + p);
        }
        List<Dir> dirs = new ArrayList<>();
        try (var s = Files.list(p)) {
            s.filter(Files::isDirectory)
                    .sorted((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()))
                    .limit(500)
                    .forEach(d -> dirs.add(new Dir(d.getFileName().toString(), d.toString())));
        } catch (IOException e) {
            throw new IllegalStateException("读取目录失败: " + p, e);
        }
        Path parent = p.getParent();
        return new BrowseView(p.toString(),
                parent == null ? null : parent.toString(),
                dirs);
    }

    /** 目录树一节：列出某目录下的子目录与文件（懒加载用）。 */
    @GetMapping("/tree")
    public TreeNode tree(@RequestParam String path) {
        Path p = Path.of(path).toAbsolutePath().normalize();
        if (!Files.isDirectory(p)) {
            throw new IllegalArgumentException("路径不是目录: " + p);
        }
        List<Dir> dirs = new ArrayList<>();
        List<Dir> files = new ArrayList<>();
        try (var s = Files.list(p)) {
            s.sorted((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()))
                    .forEach(x -> {
                        Dir d = new Dir(x.getFileName().toString(), x.toString());
                        if (Files.isDirectory(x)) dirs.add(d);
                        else files.add(d);
                    });
        } catch (IOException e) {
            throw new IllegalStateException("读取目录失败: " + p, e);
        }
        return new TreeNode(p.toString(), dirs, files);
    }

    /** 服务器静默文件：返回 path 指向文件的字节与合适的 Content-Type（供预览 iframe 使用）。 */
    @GetMapping("/serve")
    public ResponseEntity<byte[]> serve(@RequestParam String path) {
        Path p = Path.of(path).toAbsolutePath().normalize();
        if (!Files.isRegularFile(p)) {
            return ResponseEntity.badRequest().build();
        }
        try {
            byte[] body = Files.readAllBytes(p);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, mediaType(p.getFileName().toString()))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                    .body(body);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /** 保存文本文件内容：path 为目标文件绝对路径，root 为工作区根（非空时强制 path 必须落在其内）。
     *  请求体是文本原文（text/plain），原子写落盘。供工作区预览右上角「编辑」后自动保存使用。 */
    @PostMapping("/save")
    public Map<String, String> save(@RequestParam String path,
                                    @RequestParam(required = false) String root,
                                    @org.springframework.web.bind.annotation.RequestBody(required = false)
                                    String content) throws IOException {
        Path p = Path.of(path).toAbsolutePath().normalize();
        Path r = null;
        if (root != null && !root.isBlank()) {
            r = Path.of(root).toAbsolutePath().normalize();
            if (!p.startsWith(r)) {
                throw new IllegalArgumentException("仅允许保存工作区内的文件");
            }
        }
        // 文件不存在：在工作区根内运行的编辑器（root 非空）允许自动补建缺失文件（含父目录），
        // 否则（无 root）保持原来的「文件不存在」报错，防止任意路径被凭空写盘。
        if (!Files.isRegularFile(p)) {
            if (r == null) {
                throw new IllegalArgumentException("文件不存在: " + p);
            }
            Path parent = p.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        }
        Files.writeString(p, content == null ? "" : content, StandardCharsets.UTF_8);
        Map<String, String> out = new LinkedHashMap<>();
        out.put("ok", "true");
        out.put("path", p.toString());
        return out;
    }

    /** 按文件扩展名推断媒介类型；未知则回退 application/octet-stream。 */
    private static String mediaType(String name) {
        int i = name.lastIndexOf('.');
        if (i < 0) return "application/octet-stream";
        switch (name.substring(i + 1).toLowerCase()) {
            case "html": case "htm": return "text/html; charset=utf-8";
            case "css": return "text/css; charset=utf-8";
            case "js": case "mjs": return "text/javascript; charset=utf-8";
            case "json": return "application/json";
            case "svg": return "image/svg+xml";
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "ico": return "image/x-icon";
            default: return "application/octet-stream";
        }
    }

    /** 上传文件到指定目录：path 为目标目录绝对路径，multipart 'file' 为文件内容。
     * 文件名净化后落到目标目录内，杜绝 ../ 或绝对路径穿越。
     */
    @PostMapping("/file")
    public Map<String, String> upload(@RequestParam String path, @RequestPart("file") MultipartFile file) throws IOException {
        Path dir = Path.of(path).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("目录不存在: " + dir);
        }
        Path target = dir.resolve(sanitizeFileName(file.getOriginalFilename())).normalize();
        if (!target.startsWith(dir)) {
            throw new IllegalArgumentException("非法文件名: " + file.getOriginalFilename());
        }
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        Map<String, String> out = new LinkedHashMap<>();
        out.put("name", target.getFileName().toString());
        out.put("path", target.toString());
        return out;
    }

    /** 净化文件名：去掉路径分隔符与非法控制字符，仅保留末段名称。 */
    private static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) name = "upload";
        String n = name.replace('\\', '/');
        int i = n.lastIndexOf('/');
        if (i >= 0) n = n.substring(i + 1);
        n = n.replaceAll("[\\x00-\\x1f]", "").trim();
        if (n.isBlank() || n.equals(".") || n.equals("..")) n = "upload";
        return n;
    }

    /** 树节点结果。 */
    public record TreeNode(String path, List<Dir> dirs, List<Dir> files) {
    }

    /** 目录条目。 */
    public record Dir(String name, String path) {
    }

    /** 浏览结果：当前目录 + 其父目录 + 子目录列表。 */
    public record BrowseView(String current, String parent, List<Dir> dirs) {
    }
}