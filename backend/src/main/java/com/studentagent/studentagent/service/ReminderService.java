package com.studentagent.studentagent.service;

import com.studentagent.studentagent.entity.MemoryRecord;
import com.studentagent.studentagent.entity.StudentProfile;
import com.studentagent.studentagent.entity.StudyEvent;
import com.studentagent.studentagent.mapper.MemoryRecordMapper;
import com.studentagent.studentagent.mapper.ProfileMapper;
import com.studentagent.studentagent.mapper.StudyEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能复习提醒服务 — 基于长时记忆主动推送提醒
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReminderService {

    private final MemoryRecordMapper memoryRecordMapper;
    private final ProfileMapper profileMapper;
    private final StudyEventMapper studyEventMapper;

    /**
     * 检查所有提醒规则，返回提醒文案列表
     */
    public List<String> checkReminders(Long userId) {
        List<String> reminders = new ArrayList<>();

        try {
            String forgotten = checkForgottenKnowledge(userId);
            if (forgotten != null) {
                reminders.add(forgotten);
                log.info("用户{} 规则1命中: {}", userId, forgotten);
            }
        } catch (Exception e) {
            log.warn("知识点遗忘检查失败: {}", e.getMessage());
        }

        try {
            String exam = checkExamCountdown(userId);
            if (exam != null) {
                reminders.add(exam);
                log.info("用户{} 规则2命中: {}", userId, exam);
            }
        } catch (Exception e) {
            log.warn("考试倒计时检查失败: {}", e.getMessage());
        }

        try {
            String weak = checkWeakSubjects(userId);
            if (weak != null) {
                reminders.add(weak);
                log.info("用户{} 规则3命中: {}", userId, weak);
            }
        } catch (Exception e) {
            log.warn("薄弱点检查失败: {}", e.getMessage());
        }

        Collections.shuffle(reminders);
        // 每次只弹出1条提醒
        if (reminders.size() > 1) {
            reminders = reminders.subList(0, 1);
        }
        log.info("用户{} 提醒检查结果: {} 条 -> {}", userId, reminders.size(), reminders);
        return reminders;
    }

    // ==================== 规则1：知识点遗忘提醒 ====================

    /**
     * 从长时记忆中找出超过3天未提及的知识点
     */
    private String checkForgottenKnowledge(Long userId) {
        List<MemoryRecord> records = memoryRecordMapper.findByUserId(userId);
        if (records == null || records.isEmpty()) return null;

        // 按时间倒序
        records.sort((a, b) -> b.getCreateTime().compareTo(a.getCreateTime()));
        LocalDateTime threeDaysAgo = LocalDateTime.now().minusDays(3);

        // 收集最近3天提及的主题
        Set<String> recentTopics = new HashSet<>();
        // 收集3天前的主题及其最早出现时间
        Map<String, LocalDateTime> oldTopicTime = new LinkedHashMap<>();

        for (MemoryRecord r : records) {
            String topic = extractTopic(r.getMemoryText());
            if (topic == null) continue;

            if (r.getCreateTime().isAfter(threeDaysAgo)) {
                recentTopics.add(topic);
            } else if (!recentTopics.contains(topic)) {
                // 保留最晚的一条记录时间
                if (!oldTopicTime.containsKey(topic)
                        || r.getCreateTime().isAfter(oldTopicTime.get(topic))) {
                    oldTopicTime.put(topic, r.getCreateTime());
                }
            }
        }

        // 找一个最久未提及的
        for (Map.Entry<String, LocalDateTime> entry : oldTopicTime.entrySet()) {
            long days = ChronoUnit.DAYS.between(entry.getValue().toLocalDate(), LocalDate.now());
            if (days > 3 && days < 365) {
                return "你" + days + "天没复习" + entry.getKey() + "了，要来一题吗？";
            }
        }

        return null;
    }

    /**
     * 从记忆文本中提取知识点关键词
     * 记忆文本格式: {类别}-{主题}-{详情}，如 "薄弱科目-高等数学-多元微积分不足"
     * 提取中间的主题部分作为知识点名称
     */
    private String extractTopic(String text) {
        if (text == null || text.isBlank()) return null;

        // 去掉类别前缀: 薄弱科目、知识掌握、学习目标、学习习惯、考试计划、学习偏好
        text = text.replaceAll("^(薄弱科目|知识掌握|学习目标|学习习惯|考试计划|学习偏好)\\s*-\\s*", "").trim();

        // 如果还有 "-" 分隔符，取第一段作为主题（即 {主题}-{详情} 格式）
        int dashIdx = text.indexOf('-');
        if (dashIdx > 0) {
            text = text.substring(0, dashIdx).trim();
        }

        // 提取中文 + 英文关键词，去除无意义字符
        String result = text.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z]", "").trim();
        // 过滤无意义的占位词
        if (result.length() < 2 || result.equals("未明确") || result.equals("暂无") || result.equals("无")) {
            return null;
        }
        if (result.length() > 12) {
            result = result.substring(0, 12);
        }
        return result.length() >= 2 ? result : null;
    }

    // ==================== 规则2：考试倒计时 ====================

    /**
     * 从学业档案 + 日历事件中检查是否有30天内的考试
     */
    private String checkExamCountdown(Long userId) {
        LocalDate today = LocalDate.now();

        // 1. 先查学业档案的考试计划文本
        StudentProfile profile = profileMapper.findByUserId(userId);
        if (profile != null && profile.getExamPlans() != null && !profile.getExamPlans().isBlank()) {
            String examPlans = profile.getExamPlans();

            // 模式1：YYYY年M月 XX考试
            Pattern p1 = Pattern.compile("(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*([\\u4e00-\\u9fa5]{2,10})?");
            Matcher m1 = p1.matcher(examPlans);
            while (m1.find()) {
                int year = Integer.parseInt(m1.group(1));
                int month = Integer.parseInt(m1.group(2));
                LocalDate examDate = LocalDate.of(year, month, 1);
                long days = ChronoUnit.DAYS.between(today, examDate.minusDays(1)) + 1;
                if (days > 0 && days <= 30) {
                    String examName = m1.group(3);
                    if (examName == null || examName.isEmpty()) examName = "考试";
                    return "距离" + examName + "还有" + days + "天，建议开启冲刺模式";
                }
            }

            // 模式2：YYYY-MM-DD XX考试
            Pattern p2 = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})\\s*([\\u4e00-\\u9fa5]{2,10})?");
            Matcher m2 = p2.matcher(examPlans);
            while (m2.find()) {
                LocalDate examDate = LocalDate.of(Integer.parseInt(m2.group(1)),
                        Integer.parseInt(m2.group(2)), Integer.parseInt(m2.group(3)));
                long days = ChronoUnit.DAYS.between(today, examDate);
                if (days > 0 && days <= 30) {
                    String examName = m2.group(4);
                    if (examName == null || examName.isEmpty()) examName = "考试";
                    return "距离" + examName + "还有" + days + "天，建议开启冲刺模式";
                }
            }

            // 模式3：M月D日 XX考试
            Pattern p3 = Pattern.compile("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日\\s*([\\u4e00-\\u9fa5]{2,10})?");
            Matcher m3 = p3.matcher(examPlans);
            while (m3.find()) {
                int month = Integer.parseInt(m3.group(1));
                int day = Integer.parseInt(m3.group(2));
                int year = today.getYear();
                LocalDate examDate = LocalDate.of(year, month, day);
                if (examDate.isBefore(today)) examDate = LocalDate.of(year + 1, month, day);
                long days = ChronoUnit.DAYS.between(today, examDate);
                if (days > 0 && days <= 30) {
                    String examName = m3.group(3);
                    if (examName == null || examName.isEmpty()) examName = "考试";
                    return "距离" + examName + "还有" + days + "天，建议开启冲刺模式";
                }
            }
        }

        // 2. 查日历事件，只匹配考试/测试类（过滤掉普通复习任务）
        List<StudyEvent> upcomingEvents = studyEventMapper.findByDateRange(
                userId, today, today.plusDays(30));
        if (upcomingEvents != null) {
            for (StudyEvent event : upcomingEvents) {
                String title = event.getTitle();
                // 只匹配考试相关事件
                if (title == null || !title.matches(".*(考试|测试|期末|期中|考核|模考|统考).*")) {
                    continue;
                }
                long days = ChronoUnit.DAYS.between(today, event.getEventDate());
                if (days > 0 && days <= 30) {
                    // 去掉"复习"前缀和多余标点
                    title = title.replaceAll("^复习", "").replaceAll("[：:\\s]+$", "").trim();
                    if (title.length() > 10) title = title.substring(0, 10);
                    return "距离" + title + "还有" + days + "天，建议开启冲刺模式";
                }
            }
        }

        return null;
    }

    // ==================== 规则3：薄弱点反复提及 ====================

    /**
     * 检查学业档案中的薄弱点是否在长时记忆中被反复提及
     */
    private String checkWeakSubjects(Long userId) {
        StudentProfile profile = profileMapper.findByUserId(userId);
        if (profile == null || profile.getWeakSubjects() == null || profile.getWeakSubjects().isBlank()) {
            return null;
        }

        List<MemoryRecord> records = memoryRecordMapper.findByUserId(userId);
        if (records == null || records.isEmpty()) return null;

        String weakSubjects = profile.getWeakSubjects();
        // 按常见分隔符拆分
        String[] keywords = weakSubjects.split("[，,、；;。\\n\\r]+");

        for (String keyword : keywords) {
            keyword = keyword.trim();
            if (keyword.length() < 2) continue;

            int count = 0;
            for (MemoryRecord record : records) {
                if (record.getMemoryText() != null && record.getMemoryText().contains(keyword)) {
                    count++;
                }
            }

            if (count >= 3) {
                return keyword + "已经被你标记为薄弱点" + count + "次，需要专项突破吗？";
            }
        }

        return null;
    }
}
