package io.memobservatory.exoskeleton.drafter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 撰写器草稿缓存（B20.3）：把「同一画像同一文本」这条契约从**进程内**扩到**跨重启**。
 *
 * <p><b>为什么需要它</b>：契约（§4.5）要求对同一个画像产出同样的文本。模型侧只有 {@code temperature=0}
 * 是**近似**确定，严格兑现原先只靠 {@link LlmRuleDrafter} 里的进程内 {@code ConcurrentHashMap}——
 * 进程一重启缓存就没了，同一画像是否逐字复现又变成模型侧的事。故把首个成功结果落库：
 * 重启后先查表，命中就直接回，连模型都不问（既兑现契约，也省一笔钱）。
 *
 * <p><b>两级</b>：L1 进程内 {@code ConcurrentHashMap}（省掉同一进程内的重复 DB 往返）；
 * L2 {@code memory_draft_cache} 表（重启后由它兜住）。命中 L2 会回填 L1。
 *
 * <p><b>只记不判</b>：本类不进注入、不进闸门、不影响任何判据——它只是「模型说过什么」的备忘录。
 * 要让某簇重新生成，删掉那行即可。
 *
 * <p><b>失效不阻断</b>：库读写失败一律降级为「当作没缓存」（读）或「只留 L1」（写），
 * 只记日志。缓存是**优化**，它坏了不该让起草整条链失败——与 {@link LlmRuleDrafter} 的 fail-open 同一纪律。
 */
@Component
public class DraftCache {

    private static final Logger log = LoggerFactory.getLogger(DraftCache.class);

    private static final String SQL_GET =
            "SELECT body FROM memory_draft_cache WHERE cluster_key = ?";
    /** 同簇句子一旦定下就不再改写——确定性的落点（见 schema.sql 的注释）。 */
    private static final String SQL_PUT =
            "INSERT INTO memory_draft_cache (cluster_key, body, model) VALUES (?, ?, ?) "
                    + "ON CONFLICT (cluster_key) DO NOTHING";

    private final JdbcTemplate jdbc;

    /** L1：进程内，省掉重复 DB 往返。 */
    private final Map<String, String> memory = new ConcurrentHashMap<>();

    public DraftCache(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 取该簇上一份草稿：L1 → L2。没有（或库不可用）则 empty——调用方据此去问模型。 */
    public Optional<String> get(String clusterKey) {
        String hit = memory.get(clusterKey);
        if (hit != null) {
            return Optional.of(hit);
        }
        try {
            List<String> rows = jdbc.queryForList(SQL_GET, String.class, clusterKey);
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            memory.put(clusterKey, rows.get(0));   // 回填 L1
            return Optional.of(rows.get(0));
        } catch (Exception e) {
            // 库不可用＝当作没缓存：让撰写器照常去问模型，而不是把整轮起草拖垮
            log.warn("草稿缓存读取失败，本簇当作未缓存：{}", e.toString());
            return Optional.empty();
        }
    }

    /** 记下这次产出的草稿。库写失败只记日志——L1 已生效，本轮内的确定性不受影响。 */
    public void put(String clusterKey, String body, String model) {
        memory.put(clusterKey, body);
        try {
            jdbc.update(SQL_PUT, clusterKey, body, model);
        } catch (Exception e) {
            log.warn("草稿缓存落库失败（进程内仍生效，本簇草稿已定）：{}", e.toString());
        }
    }
}
