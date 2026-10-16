package io.memobservatory.guard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 架构守则测试的公共支撑（零依赖，仅 JDK）。
 *
 * <p>守则测试（S2 依赖方向 / S3 表所有权）要读**源码文本**而非编译产物，故需要：
 * ① 从测试运行时的工作目录上溯定位仓库根；② 剥离注释，避免把注释里出现的
 * {@code UPDATE memory_turns} 之类字样误判成真实写语句（否则守则会被注释噪声骗红）。
 */
final class GuardSupport {

    private GuardSupport() {
    }

    /** 从当前工作目录上溯，找到同时含 {@code mo-server} 与 {@code mo-exoskeleton} 的目录。 */
    static Path repoRoot() {
        Path start = Paths.get("").toAbsolutePath();
        for (Path p = start; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("mo-server")) && Files.isDirectory(p.resolve("mo-exoskeleton"))) {
                return p;
            }
        }
        throw new IllegalStateException("无法定位仓库根：从 " + start + " 上溯未找到含 mo-server 与 mo-exoskeleton 的目录");
    }

    /** 递归收集某个源码根下的全部 {@code .java} 文件（排序，保证断言输出稳定）。 */
    static List<Path> javaFiles(Path sourceRoot) throws IOException {
        if (!Files.isDirectory(sourceRoot)) {
            throw new IllegalStateException("源码目录不存在：" + sourceRoot);
        }
        try (Stream<Path> s = Files.walk(sourceRoot)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
    }

    /**
     * 剥离 Java 注释（行注释 {@code //}、块注释 {@code /*} 含 javadoc），保留字符串字面量、
     * 字符字面量与文本块 {@code """} 的内容——因为 SQL 语句正是写在它们里面。
     *
     * <p>被剥离的注释位置以一个空格占位，避免相邻 token 因拼接而误连。
     */
    static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                i += 2;
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
                out.append(' ');
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
                out.append(' ');
                continue;
            }
            if (c == '"' && i + 2 < n && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
                out.append("\"\"\"");
                i += 3;
                int end = src.indexOf("\"\"\"", i);
                if (end < 0) {
                    out.append(src, i, n);
                    i = n;
                } else {
                    out.append(src, i, end + 3);
                    i = end + 3;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                char quote = c;
                out.append(c);
                i++;
                while (i < n) {
                    char d = src.charAt(i);
                    out.append(d);
                    if (d == '\\' && i + 1 < n) {
                        out.append(src.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    i++;
                    if (d == quote || d == '\n') {
                        break;
                    }
                }
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}