package com.studentagent.studentagent.service;

import com.studentagent.studentagent.dto.StatisticsVO;
import com.studentagent.studentagent.entity.MemoryRecord;
import com.studentagent.studentagent.mapper.MemoryRecordMapper;
import com.studentagent.studentagent.mapper.MessageMapper;
import com.studentagent.studentagent.mapper.StudyEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 学习数据看板统计服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatisticsService {

    private final MemoryRecordMapper memoryRecordMapper;
    private final MessageMapper messageMapper;
    private final StudyEventMapper studyEventMapper;

    private static final int TREND_DAYS = 7;
    /** 每日完成趋势柱状图天数 */
    private static final int COMPLETION_TREND_DAYS = 14;

    // 科目关键词映射
    private static final Map<String, List<String>> SUBJECT_KEYWORDS = new LinkedHashMap<>();
    static {
        SUBJECT_KEYWORDS.put("数据结构", List.of("数据结构", "DS", "二叉树", "图论", "排序算法", "链表", "栈", "队列", "哈希表", "B树", "B+树", "红黑树", "并查集"));
        SUBJECT_KEYWORDS.put("操作系统", List.of("操作系统", "OS", "进程", "线程", "死锁", "内存管理", "文件系统", "os", "调度", "虚拟内存", "页面置换", "信号量"));
        SUBJECT_KEYWORDS.put("计算机网络", List.of("计算机网络", "计网", "tcp", "http", "udp", "dns", "三次握手", "网络协议", "路由", "ip地址", "子网掩码", "tls"));
        SUBJECT_KEYWORDS.put("线性代数", List.of("线性代数", "线代", "矩阵", "行列式", "特征值", "向量空间", "线性变换", "特征向量", "二次型"));
        SUBJECT_KEYWORDS.put("高等数学", List.of("高等数学", "高数", "微积分", "导数", "积分", "级数", "微分方程", "多元函数", "中值定理", "不定积分"));
        SUBJECT_KEYWORDS.put("概率论与统计", List.of("概率论", "数理统计", "概率", "随机变量", "分布函数", "大数定律", "中心极限", "假设检验", "参数估计"));
        SUBJECT_KEYWORDS.put("计算机组成原理", List.of("计算机组成原理", "计组", "cpu", "存储器", "总线", "指令系统", "流水线", "cache", "中断", "冯诺依曼"));
        SUBJECT_KEYWORDS.put("离散数学", List.of("离散数学", "离散", "集合论", "数理逻辑", "图论基础", "组合数学", "代数系统", "命题逻辑", "谓词逻辑"));
        SUBJECT_KEYWORDS.put("英语", List.of("英语", "四级", "六级", "单词", "听力", "作文", "翻译", "语法", "阅读理解", "雅思", "托福"));
        SUBJECT_KEYWORDS.put("数据库", List.of("数据库", "sql", "mysql", "索引", "事务", "范式", "redis", "mongodb", "acid", "锁机制", "分库分表"));
        SUBJECT_KEYWORDS.put("政治", List.of("政治", "马原", "毛中特", "思修", "史纲", "时政", "考研政治"));
        SUBJECT_KEYWORDS.put("编程语言", List.of("java", "python", "c语言", "c++", "go语言", "javascript", "rust", "面向对象", "多线程编程"));
        SUBJECT_KEYWORDS.put("408综合", List.of("408", "考研408", "408真题", "408算法", "408计组", "408操作系统", "408计算机网络"));
        SUBJECT_KEYWORDS.put("编译原理", List.of("编译原理", "编译器", "词法分析", "语法分析", "语义分析"));
        SUBJECT_KEYWORDS.put("软件工程", List.of("软件工程", "设计模式", "uml", "敏捷开发", "需求分析"));
        SUBJECT_KEYWORDS.put("人工智能", List.of("人工智能", "机器学习", "深度学习", "神经网络", "nlp", "计算机视觉"));
    }

    // 薄弱关键词（去掉过于宽泛的单字词）
    private static final List<String> WEAKNESS_KEYWORDS = List.of(
            "薄弱", "不会", "搞不懂", "不熟练", "比较弱", "不太会", "很弱", "不擅长", "困难", "头疼", "吃力", "基础差", "没掌握"
    );

    /**
     * 获取学习数据看板全部数据
     */
    public StatisticsVO getOverview(Long userId) {
        List<MemoryRecord> allMemories = memoryRecordMapper.findByUserId(userId);

        return new StatisticsVO(
                buildSubjectDistribution(allMemories),
                buildWeaknessRadar(allMemories),
                buildActivityTrend(userId),
                buildMemoryGrowth(userId),
                buildProgressOverview(userId),
                buildCompletionTrend(userId)
        );
    }

    /**
     * 学习进度总览 — 基于日历任务完成情况（completed 字段，数据事实，非 LLM 提取）
     * 返回：累计完成/总任务/未完成/完成率/本周完成/连续学习天数
     */
    private Map<String, Object> buildProgressOverview(Long userId) {
        int completed = studyEventMapper.countCompleted(userId);
        int pending = studyEventMapper.countPending(userId);
        int total = completed + pending;
        int rate = total == 0 ? 0 : Math.round(completed * 100f / total);

        // 本周完成：从本周一（周一为一周起点）算起
        LocalDate today = LocalDate.now();
        LocalDate weekStart = today.minusDays(today.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue());
        int thisWeek = studyEventMapper.countCompletedSince(userId, weekStart);

        // 连续学习天数：有完成任务的连续日期数；今天尚未完成则从昨天起算（不视为中断）
        List<LocalDate> doneDates = studyEventMapper.findCompletedDates(userId);
        Set<LocalDate> doneSet = new HashSet<>(doneDates);
        LocalDate cursor = doneSet.contains(today) ? today : today.minusDays(1);
        int streak = 0;
        while (doneSet.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("total", total);
        map.put("completed", completed);
        map.put("pending", pending);
        map.put("completionRate", rate);
        map.put("thisWeekCompleted", thisWeek);
        map.put("streak", streak);
        return map;
    }

    /**
     * 每日完成趋势（近14天）— 每天勾选完成任务的数量
     */
    private List<Map<String, Object>> buildCompletionTrend(Long userId) {
        LocalDate since = LocalDate.now().minusDays(COMPLETION_TREND_DAYS - 1L);
        List<Map<String, Object>> raw = studyEventMapper.countCompletedByDay(userId, since);
        return fillDateGap(raw, COMPLETION_TREND_DAYS);
    }

    /**
     * 从文本中提取包含的科目（两步走）：
     * 1. 优先解析 LLM 结构化记忆格式（薄弱科目-数据结构-xxx）
     * 2. 兜底关键词全文匹配
     */
    private String extractSubject(String text) {
        // 1. 优先从 LLM 结构化记忆格式中提取科目名
        String[] parts = text.split("-", 3);
        if (parts.length >= 2) {
            String candidate = parts[1].trim();
            if (candidate.length() >= 2 && candidate.length() <= 20) {
                for (Map.Entry<String, List<String>> entry : SUBJECT_KEYWORDS.entrySet()) {
                    if (entry.getKey().equals(candidate) || entry.getValue().stream().anyMatch(kw -> kw.equalsIgnoreCase(candidate))) {
                        return entry.getKey();
                    }
                }
            }
        }

        // 2. 兜底：关键词全文匹配
        String lower = text.toLowerCase();
        for (Map.Entry<String, List<String>> entry : SUBJECT_KEYWORDS.entrySet()) {
            for (String kw : entry.getValue()) {
                if (lower.contains(kw.toLowerCase())) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    /**
     * 科目分布饼图 — 基于 LLM 提取的记忆记录统计
     */
    private List<Map<String, Object>> buildSubjectDistribution(List<MemoryRecord> memories) {
        Map<String, Integer> subjectCount = new LinkedHashMap<>();

        for (MemoryRecord m : memories) {
            String raw = m.getMemoryText();
            if (raw == null) continue;
            String subject = extractSubject(raw);
            if (subject != null) {
                subjectCount.merge(subject, 1, Integer::sum);
            }
        }

        if (subjectCount.isEmpty()) {
            return List.of(Map.of("name", "暂无数据", "value", 1));
        }
        return subjectCount.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> Map.of("name", (Object) e.getKey(), "value", (Object) e.getValue()))
                .collect(Collectors.toList());
    }

    /**
     * 薄弱项雷达图 — 统计各科目中薄弱、不会等关键词的提及频率
     */
    private List<Map<String, Object>> buildWeaknessRadar(List<MemoryRecord> memories) {
        Map<String, Integer> weaknessCount = new LinkedHashMap<>();

        for (MemoryRecord m : memories) {
            String raw = m.getMemoryText();
            if (raw == null) continue;
            String text = raw.toLowerCase();
            // 检查是否包含薄弱关键词
            boolean isWeakness = WEAKNESS_KEYWORDS.stream().anyMatch(text::contains);
            if (!isWeakness) continue;

            // 归类到具体科目
            outer:
            for (Map.Entry<String, List<String>> entry : SUBJECT_KEYWORDS.entrySet()) {
                String subject = entry.getKey();
                for (String kw : entry.getValue()) {
                    if (text.contains(kw.toLowerCase())) {
                        weaknessCount.merge(subject, 1, Integer::sum);
                        break outer;
                    }
                }
            }
        }

        if (weaknessCount.isEmpty()) {
            return List.of(Map.of("name", "暂无薄弱数据", "value", 1));
        }
        return weaknessCount.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> Map.of("name", (Object) e.getKey(), "value", (Object) e.getValue()))
                .collect(Collectors.toList());
    }

    /**
     * 学习活跃趋势（近7天每日用户消息数）
     */
    private List<Map<String, Object>> buildActivityTrend(Long userId) {
        List<Map<String, Object>> raw = messageMapper.countByDay(userId, TREND_DAYS);
        return fillDateGap(raw, TREND_DAYS);
    }

    /**
     * 记忆增长曲线（近7天每日新增记忆数）
     */
    private List<Map<String, Object>> buildMemoryGrowth(Long userId) {
        List<Map<String, Object>> raw = memoryRecordMapper.countByDay(userId, TREND_DAYS);
        return fillDateGap(raw, TREND_DAYS);
    }

    /**
     * 补全缺失的日期（有些天可能没有数据，也要在图表上显示为0）
     */
    private List<Map<String, Object>> fillDateGap(List<Map<String, Object>> raw, int days) {
        Map<String, Integer> dateMap = new LinkedHashMap<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        // 初始化近N天日期
        for (int i = days - 1; i >= 0; i--) {
            String date = LocalDate.now().minusDays(i).format(fmt);
            dateMap.put(date, 0);
        }

        // 填入实际数据
        for (Map<String, Object> row : raw) {
            String date = row.get("date").toString();
            int count = ((Number) row.get("count")).intValue();
            if (dateMap.containsKey(date)) {
                dateMap.put(date, count);
            }
        }

        return dateMap.entrySet().stream()
                .map(e -> Map.of("date", (Object) e.getKey(), "count", (Object) e.getValue()))
                .collect(Collectors.toList());
    }
}
