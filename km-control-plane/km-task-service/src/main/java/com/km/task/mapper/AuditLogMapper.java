package com.km.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.km.task.domain.AuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code audit_log} 的 Mapper。
 *
 * <p>和其他 Mapper 一样是空的 —— 通用 CRUD 就够。
 *
 * <h3>幂等为什么不在这里做</h3>
 *
 * 去重的依据是表上的唯一键 {@code uk_audit_event (event_id)}。消费端用普通
 * insert, 撞键就抛 {@code DuplicateKeyException}, 由消费端把它认成"重投"。
 * 逻辑写在消费端而不是这里, 是因为"重投"是<b>消费语义</b>, 不是持久化语义 ——
 * 同样一个唯一键冲突, 换个场景(比如后台补录)可能就应该报错。
 *
 * <h3>★ 一个踩过的坑: 别用影响行数判断"是不是重投" ★</h3>
 *
 * 曾经写成 {@code INSERT ... ON DUPLICATE KEY UPDATE id = id}, 然后靠
 * "影响行数为 0 就是重复" 来判断。在 mysql 命令行里验过, ROW_COUNT() 确实
 * 返回 0, 看起来没问题。
 *
 * <p>但走 JDBC 时 MyBatis 打出来的是 {@code <== Updates: 1}。原因是
 * Connector/J 默认会设置 CLIENT_FOUND_ROWS 标志, 服务端于是回报
 * <b>"匹配到的行数"</b>而不是"实际改动的行数" —— 重复插入匹配到了一行,
 * 于是报 1。同一句 SQL, 命令行和驱动给出不同的数字。
 *
 * <p>后果不是数据错(唯一键仍然挡住了重复行), 而是<b>统计和日志全错</b>:
 * 重投被当成新入库计数, 那条"已去重"的日志永远不会出现, 运维看不到
 * 正在发生重投。这类错误比崩掉更难发现, 因为它删不掉任何东西, 只是
 * 让你对系统状态的认知是错的。
 *
 * <p>所以: 要判断"是不是重复", 就去接住唯一键冲突这个事实本身,
 * 不要去数行数。
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
