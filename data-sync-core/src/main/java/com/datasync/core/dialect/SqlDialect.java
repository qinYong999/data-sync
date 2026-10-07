package com.datasync.core.dialect;

import com.datasync.core.jdbc.KeysetPosition;
import com.datasync.core.jdbc.TableRef;
import com.datasync.core.model.enums.DbType;
import java.util.ArrayList;
import java.util.List;

/**
 * 方言：把「意图」翻译成目标数据库的 SQL 文本。
 *
 * <p><b>参数占位符约定（重要）</b>：所有 {@code build*} 方法返回的 SQL 里，
 * 需要绑定值的地方一律是 {@code ?}，值永远不拼进 SQL 文本（杜绝注入与时间格式歧义）。
 * 绑定顺序固定为：
 * <ul>
 *   <li>{@link #buildKeysetSelect}：{@code after.values()}，按顺序；第一页无参数。</li>
 *   <li>{@link #buildWatermarkSelect}：{@code [lowerExclusive?]} + {@code [upperInclusive?]} + {@code after.values()}，
 *       为空的一侧不生成占位符。</li>
 *   <li>{@link #buildInsert}/{@link #buildUpsert}：按 {@code columns} 顺序。</li>
 * </ul>
 *
 * <p><b>键集分页</b>：多列键必须做元组比较（MySQL）或元组展开（DM8），
 * 绝不允许只比较第一列；本接口不提供任何 OFFSET 分页能力（契约 D2）。
 */
public interface SqlDialect {

    DbType dbType();

    /** 引用标识符（MySQL 反引号 / DM8 双引号）；含点号时分段引用。 */
    String quote(String identifier);

    /** {@code schema.table}；schema 为空时只引用表名。 */
    String qualifySchema(String schema, String table);

    /** 查询当前用户的 SQL（MySQL: {@code SELECT CURRENT_USER()}；DM8: {@code SELECT USER FROM DUAL}）。 */
    String userName();

    /**
     * 键集分页查询：{@code SELECT ... FROM t [WHERE extra... AND keyset] ORDER BY key... LIMIT n}。
     *
     * @param selectColumns   要读的列
     * @param keyColumns      排序键（必须唯一确定顺序；多列时元组比较）
     * @param after           游标位置；{@code first()} 表示第一页
     * @param pageSize        单页行数
     * @param extraPredicates 额外的 WHERE 片段（原样拼入，仅允许引擎内部生成的可信文本）
     */
    String buildKeysetSelect(TableRef table, List<String> selectColumns, List<String> keyColumns,
                             KeysetPosition after, int pageSize, List<String> extraPredicates);

    /**
     * 水位区间查询：{@code cursor > lowerExclusive AND cursor <= upperInclusive}，
     * 叠加键集游标（顺序键 = {@code [cursorColumn] + keyColumns}）。
     *
     * @param lowerExclusive 读下界（null = 不限制）；非空时生成一个 {@code ?}
     * @param upperInclusive 读上界（null = 不限制）；非空时生成一个 {@code ?}
     */
    String buildWatermarkSelect(TableRef table, List<String> selectColumns, String cursorColumn,
                                String lowerExclusive, String upperInclusive, List<String> keyColumns,
                                KeysetPosition after, int pageSize);

    String buildCount(TableRef table, List<String> extraPredicates);

    String buildTruncate(TableRef table);

    String buildDeleteAll(TableRef table);

    String buildInsert(TableRef table, List<String> columns);

    /** 幂等 upsert：MySQL 用 ON DUPLICATE KEY UPDATE，DM8 用 MERGE INTO。 */
    String buildUpsert(TableRef table, List<String> columns, List<String> keyColumns);

    /** SWAP 策略：按 source 建一张同构空表；DM8 抛 UnsupportedOperationException。 */
    String buildCreateTableLike(TableRef source, TableRef newTable);

    String buildRenameTable(TableRef from, TableRef to);

    String buildDropTable(TableRef table);

    String buildMaxValue(TableRef table, String column);

    /** 限量语法：MySQL {@code LIMIT n}；DM8 由实现自行处理。 */
    String limitClause(int pageSize);

    /**
     * JDBC 流式读取的 fetchSize。
     *
     * <p>MySQL 必须用 {@link Integer#MIN_VALUE} 才会走流式（否则驱动会把整个结果集读进内存，
     * 10 万行以上直接 OOM）；其他方言用正数每批拉取。
     */
    default int streamFetchSize() {
        return 1000;
    }

    /**
     * 单条语句最多能写多少行（1 = 只能逐行 {@code addBatch}）。
     *
     * <p>存在的理由：{@code PreparedStatement.addBatch()} 的性能**完全取决于连接参数**
     * {@code rewriteBatchedStatements}——不开这个参数时，1000 行的 upsert 会退化成 1000 次独立往返
     * （本机实测：240ms vs 30ms，**8 倍**）。core 不能假设平台侧的 URL 一定开了这个参数，
     * 因此对支持的方言直接生成多行 {@code VALUES (?,?),(?,?),...}，把性能握在自己手里。
     *
     * @param columnCount 每行的列数（用于按占位符上限切分）
     */
    default int maxRowsPerStatement(int columnCount) {
        return 1;
    }

    /** 多行 VALUES 的普通插入；默认退化到单行（调用方需按 {@link #maxRowsPerStatement} 自行切分）。 */
    default String buildInsertMultiRow(TableRef table, List<String> columns, int rowCount) {
        return buildInsert(table, columns);
    }

    /** 多行 VALUES 的幂等 upsert；默认退化到单行（调用方需按 {@link #maxRowsPerStatement} 自行切分）。 */
    default String buildUpsertMultiRow(TableRef table, List<String> columns, List<String> keyColumns,
                                       int rowCount) {
        return buildUpsert(table, columns, keyColumns);
    }

    /**
     * 键集游标谓词（不含 WHERE 关键字）；{@code after} 为第一页时返回空串。
     *
     * <p><b>多列键必须用"展开式 OR"，禁止写成行构造器 {@code (k1,k2) &gt; (?,?)}。</b>
     * 单列退化为 {@code k1 &gt; ?}。
     *
     * <p>占位符个数 = <b>n(n+1)/2</b>（2 列 3 个、3 列 6 个），参数必须用
     * {@link #buildKeysetParameters} 生成——两者是一对，不允许各写一套。
     *
     * <h4>为什么不能用行构造器（真机实测，MySQL 8.0.46）</h4>
     * <pre>
     * 表：100 万行，索引 idx_upd(updated_at, id)
     * 语句： WHERE updated_at &lt;= ? AND (updated_at, id) &gt; (?, ?)   ORDER BY updated_at, id LIMIT 1000
     *
     * 行构造器形态（错误）：
     *   Limit: 1000 row(s) (actual time=195..195 rows=0)
     *     -&gt; Filter: ((updated_at, id) &gt; (...))  (rows=498146)
     *        -&gt; Covering index range scan over (updated_at &lt;= ...)  (rows=800000)
     *   → 优化器**不会**把行构造器比较下推成索引范围访问：先把上界内的 80 万条索引项全扫出来，
     *     再逐条 Filter。实测 170~210 ms/页。
     *
     * 展开式 OR（正确）：
     *   Limit: 1000 row(s) (actual time=0.0323..0.0323 rows=0)
     *     -&gt; Covering index range scan
     *        over ((updated_at = '...' AND id &gt; ...) OR ('...' &lt; updated_at &lt;= '...'))  (rows=2)
     *   → 精确定位到游标，只读 2 行；实测 0.0216~0.0272 ms/页。
     *
     * 差距约 8000 倍；1000 页就是 190 秒 vs 0.02 秒，正是"D1 增量同步只有 1500 行/秒"的根因。
     * 达梦 DM8 的可下推性更无保证，因此两个方言共用这一份默认实现。
     * <b>不要"简化"回元组写法</b>——{@code DialectKeysetPredicateTest} 会拦。
     * </pre>
     */
    default String buildKeysetPredicate(List<String> keyColumns, KeysetPosition after) {
        if (after == null || after.isFirst()) {
            return "";
        }
        requireKeysetShape(keyColumns, after);
        if (keyColumns.size() == 1) {
            return quote(keyColumns.get(0)) + " > ?";
        }
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < keyColumns.size(); i++) {
            if (i > 0) {
                sb.append(" OR ");
            }
            sb.append('(');
            for (int j = 0; j < i; j++) {
                sb.append(quote(keyColumns.get(j))).append(" = ? AND ");
            }
            sb.append(quote(keyColumns.get(i))).append(" > ?)");
        }
        return sb.append(')').toString();
    }

    /**
     * {@link #buildKeysetPredicate} 对应的参数列表（按占位符出现顺序展开）。
     *
     * <pre>
     * 谓词：(k1 &gt; ?) OR (k1 = ? AND k2 &gt; ?) OR (k1 = ? AND k2 = ? AND k3 &gt; ?)
     * 参数：[v1]   +   [v1, v2]              +   [v1, v2, v3]
     * </pre>
     *
     * <p>第一页（{@link KeysetPosition#isFirst()}）返回空列表。
     */
    default List<Object> buildKeysetParameters(List<String> keyColumns, KeysetPosition after) {
        if (after == null || after.isFirst()) {
            return List.of();
        }
        requireKeysetShape(keyColumns, after);
        List<Object> values = after.values();
        List<Object> out = new ArrayList<>(values.size() * (values.size() + 1) / 2);
        for (int i = 0; i < values.size(); i++) {
            for (int j = 0; j <= i; j++) {
                out.add(values.get(j));
            }
        }
        return out;
    }

    /** 谓词与参数的共同前置校验，避免两者对列数/值个数的理解不一致。 */
    private void requireKeysetShape(List<String> keyColumns, KeysetPosition after) {
        if (keyColumns == null || keyColumns.isEmpty()) {
            throw new IllegalArgumentException("键集分页缺少排序键列");
        }
        if (keyColumns.size() != after.size()) {
            throw new IllegalArgumentException("键集分页排序列数(" + keyColumns.size()
                    + ")与游标值个数(" + after.size() + ")不一致");
        }
    }
}
