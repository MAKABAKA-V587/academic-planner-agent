package com.studentagent.studentagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studentagent.studentagent.entity.StudyEvent;
import com.studentagent.studentagent.mapper.StudyEventMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 学习日历服务 — CRUD + AI自动提取
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CalendarService {

    private final StudyEventMapper studyEventMapper;
    // 懒加载注入：CalendarService → MemoryExtractService → ProfileService → ChatClient → LearningPlanTool → CalendarService
    // 存在构造依赖环，@Lazy 延迟解析以断开循环
    @Lazy
    @Autowired
    private MemoryExtractService memoryExtractService;
    @Lazy
    @Autowired
    private ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String EVENT_EXTRACT_PROMPT = """
            你是一个日程提取器。从文本中提取所有带明确日期的日程任务，当前日期是%s。
            
            【提取规则】
            1. 提取每一个具体任务，不要只提取阶段/周名称。例如：
               "第一周（8.1-8.7）
                - 复习基础数据结构
                - 学习排序算法"
               → 应提取为2条独立任务，不是1条"第一周"。
            2. 标题保留完整描述，不要过度精简。"复习基础数据结构：数组、链表、栈、队列"就保留原样。
            3. 日期从最近的上级标题继承。如"第一周（8.1-8.7）"下的子任务都用8.1-8.7。
            4. 已提取为子任务的，不要再重复提取其上级阶段名称。
            5. 纯阶段名称（如"基础阶段"）无具体内容的用"plan"；具体学习任务用"task"。
            6. 【重要】识别日偏移格式：D1=今天(即%s), D2=明天, D3=后天...D{n}=今天+(n-1)天。
               例如表格中"D3"对应日期为今天+2天。将D{n}转换为yyyy-MM-dd格式。
            7. 【重要】全局日期继承：当文本整体只有一个明确日期（如标题"今日计划（2026-08-06）""推荐学习计划（2026-08-06）""8月6日安排"）时，用它作为所有任务的默认日期；任务只带时间段（如"9:00-10:30：复习链表核心操作""上午：背单词"）也提取为当天事件，标题去掉时间段前缀。文本含"今日/今天"字样且无其他日期时，默认日期为今天。禁止编造：只有整个文本既无日期也无"今日/今天"字样时才返回[]。
            
            JSON字段：
            - title: 完整任务描述
            - date: 开始日期(yyyy-MM-dd)
            - endDate: 结束日期(yyyy-MM-dd)，单日为null
            - type: "task"(具体任务)、"plan"(学习计划)、"exam"(考试)、"review"(复习/艾宾浩斯复习)
            复习类（标题含"复习/巩固/回顾"）用"review"，考试/测验类用"exam"。
            
            如果没有日程事件返回[]。只输出JSON。
            
            示例：
            输入："第一周（8.1-8.7）- 复习数据结构 - 学习排序 第二周（8.8-8.14）- 复习树结构"
            输出：[{"title":"复习数据结构","date":"2026-08-01","endDate":"2026-08-07","type":"task"},{"title":"学习排序","date":"2026-08-01","endDate":"2026-08-07","type":"task"},{"title":"复习树结构","date":"2026-08-08","endDate":"2026-08-14","type":"task"}]
            """;

    private static final List<String> PLAN_COLORS = List.of(
            "#409EFF", "#E6A23C", "#67C23A", "#F56C6C", "#34495E",
            "#1ABC9C", "#E74C3C", "#3498DB", "#2ECC71", "#F39C12"
    );
    private static final List<String> TASK_COLORS = List.of(
            "#67C23A", "#1ABC9C", "#3498DB", "#E6A23C", "#34495E",
            "#2ECC71", "#409EFF", "#F39C12", "#E74C3C", "#F56C6C"
    );

    /** 基于标题哈希选颜色，同标题始终同色 */
    private String pickColor(String title, List<String> palette) {
        if (title == null || title.isEmpty()) return palette.get(0);
        long hash = 0;
        for (int i = 0; i < title.length(); i++) {
            hash = hash * 31 + title.charAt(i);
        }
        int idx = (int) (Math.abs(hash) % palette.size());
        return palette.get(idx);
    }

    /**
     * 获取指定月份的事件列表
     */
    public List<StudyEvent> getMonthEvents(Long userId, String month) {
        YearMonth ym = YearMonth.parse(month, DateTimeFormatter.ofPattern("yyyy-MM"));
        LocalDate monthStart = ym.atDay(1);
        LocalDate nextMonthStart = ym.plusMonths(1).atDay(1);
        return studyEventMapper.findByUserAndMonth(userId, monthStart, nextMonthStart);
    }

    /**
     * 获取指定日期范围内的事件
     */
    public List<StudyEvent> getEventsByDateRange(Long userId, LocalDate start, LocalDate end) {
        return studyEventMapper.findByDateRange(userId, start, end);
    }

    /**
     * 按标题和日期删除事件（AI 工具专用）
     */
    public int deleteByTitleAndDate(Long userId, String title, LocalDate date) {
        return studyEventMapper.deleteByTitleAndDate(userId, title, date);
    }

    /**
     * 按标题模糊匹配删除所有事件（不限日期）
     */
    public int deleteByTitle(Long userId, String title) {
        int count = studyEventMapper.deleteByTitle(userId, title);
        log.info("用户{} 按标题删除: title='{}', 删除了{}条", userId, title, count);
        return count;
    }

    /**
     * 手动添加事件
     */
    public StudyEvent addEvent(Long userId, StudyEvent event) {
        event.setUserId(userId);
        if (event.getEventType() == null || event.getEventType().isBlank()) {
            event.setEventType("task");
        }
        // 类型专属颜色优先：复习固定紫、考试固定红；未指定颜色时才按调色板随机
        String typeColor = switch (event.getEventType()) {
            case "review" -> "#9B59B6";
            case "exam" -> "#F56C6C";
            default -> null;
        };
        if (event.getColor() == null || event.getColor().isBlank() || "#409EFF".equals(event.getColor())) {
            if (typeColor != null) {
                event.setColor(typeColor);
            } else {
                event.setColor(pickColor(event.getTitle(), TASK_COLORS));
            }
        }
        if (event.getSource() == null || event.getSource().isBlank()) {
            event.setSource("manual");
        }
        studyEventMapper.insert(event);
        return event;
    }

    /**
     * 更新事件
     */
    @Transactional
    public void updateEvent(Long userId, Long eventId, StudyEvent event) {
        StudyEvent existing = studyEventMapper.findById(eventId);
        if (existing == null || !existing.getUserId().equals(userId)) {
            throw new RuntimeException("事件不存在或无权限");
        }
        event.setEventId(eventId);
        // 如果前端没传颜色，保留原有颜色
        if (event.getColor() == null || event.getColor().isBlank()) {
            event.setColor(existing.getColor());
        }
        // 如果标题变了且颜色没变，重新根据标题分配颜色
        else if (!existing.getTitle().equals(event.getTitle())) {
            List<String> palette = "plan".equals(event.getEventType()) ? PLAN_COLORS : TASK_COLORS;
            event.setColor(pickColor(event.getTitle(), palette));
        }
        studyEventMapper.update(event);
    }

    /**
     * 删除事件
     */
    public void deleteEvent(Long userId, Long eventId) {
        StudyEvent existing = studyEventMapper.findById(eventId);
        if (existing == null || !existing.getUserId().equals(userId)) {
            throw new RuntimeException("事件不存在或无权限");
        }
        studyEventMapper.deleteById(eventId);
    }

    // ==================== AI 自动提取 ====================

    /**
     * 清空用户所有日历事件，返回删除条数
     */
    public int clearAll(Long userId) {
        int count = studyEventMapper.deleteByUserId(userId);
        log.info("用户{} 清空日历事件{}条", userId, count);
        return count;
    }

    /**
     * 仅清空今日事件（只删今天的，不影响其他日期）
     */
    public int clearToday(Long userId) {
        int count = studyEventMapper.deleteTodayEvents(userId, LocalDate.now());
        log.info("用户{} 清空今日日历事件{}条", userId, count);
        return count;
    }

    /**
     * 从AI回复中解析学习计划阶段，自动创建日历事件。
     * 异步执行，不阻塞对话。
     */
    @Async("memoryExtractExecutor")
    public void autoExtractEvents(Long userId, String userMsg, String aiReply) {
        if (aiReply == null || aiReply.isBlank()) return;
        // 拼接用户消息 + AI回复，两边的格式都能识别
        String text = (userMsg != null ? userMsg + "\n" : "") + aiReply;
        try {
            // 正则先行（毫秒级），未命中再走 AI，减少每次聊天回复的 LLM 开销
            List<StudyEvent> extracted = parsePlanPhases(userId, text);
            if (extracted.isEmpty()) {
                extracted = aiExtractEvents(userId, text);
            }
            if (extracted.isEmpty()) {
                log.debug("用户{} AI回复中未匹配到日期阶段，回复前100字: {}", userId,
                        aiReply.substring(0, Math.min(100, aiReply.length())));
                return;
            }

            for (StudyEvent event : extracted) {
                int dup = studyEventMapper.countDuplicate(userId, event.getTitle(),
                        event.getEventDate(), event.getEndDate());
                if (dup > 0) {
                    log.debug("跳过重复事件: userId={}, title={}, {} ~ {}",
                            userId, event.getTitle(), event.getEventDate(), event.getEndDate());
                    continue;
                }
                studyEventMapper.insert(event);
                log.info("自动创建日历事件: userId={}, title={}, {} ~ {}",
                        userId, event.getTitle(), event.getEventDate(), event.getEndDate());
            }
            log.info("用户{} 自动提取日历事件{}条", userId, extracted.size());
        } catch (Exception e) {
            log.warn("AI日历事件提取失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 解析学习计划中的阶段和时间段。
     * 匹配模式：
     *   XX阶段 7.28-8.15
     *   XX阶段 7月28日-8月15日
     *   XX阶段 2026-07-28 至 2026-08-15
     *   7.28—8.15 XX阶段
     */
    /**
     * 同步提取并保存事件（供手动导入调用），返回新增条数。
     * AI 优先（准确率高），AI 失败或结果太少时用正则兜底。
     */
    public int extractAndSave(Long userId, String text) {
        try {
            log.info("用户{} 手动导入，文本前500字: {}", userId,
                    text.substring(0, Math.min(500, text.length())).replace("\n", "\\n"));

            // ① 正则先行（毫秒级、确定性）：表格 / D{n} / 阶段+日期 / 周偏移等结构化格式直接命中
            //    parsePlanPhases 内部已含相对天数表格与周偏移解析，覆盖全部结构化格式
            List<StudyEvent> extracted = parsePlanPhases(userId, text);
            log.info("用户{} 正则提取到{}条事件", userId, extracted.size());

            // ② 正则结果太少（<3条）→ AI 兜底（限时10s），合并时按标题+日期去重
            if (extracted.size() < 3) {
                try {
                    List<StudyEvent> aiEvents = aiExtractEvents(userId, text);
                    log.info("用户{} AI兜底提取到{}条事件", userId, aiEvents.size());
                    for (StudyEvent e : aiEvents) {
                        boolean dup = extracted.stream().anyMatch(x ->
                                x.getTitle().equals(e.getTitle()) && x.getEventDate().equals(e.getEventDate()));
                        if (!dup) extracted.add(e);
                    }
                } catch (Exception e) {
                    log.warn("用户{} AI兜底提取失败: {}", userId, e.getMessage());
                }
            }

            // 手动导入：不按日期范围整体删除（否则会误删该时间段内其他主题的计划），
            // 仅在下方按「同标题 + 同日期范围」清理旧版
            int saved = 0;
            for (StudyEvent event : extracted) {
                // 重新导入同一计划：只清理同标题且在本次日期范围内的旧 AI 事件（处理日期修正场景），
                // 不误删其他日期/其他主题的同名任务
                LocalDate dStart = event.getEventDate();
                LocalDate dEnd = event.getEndDate() != null ? event.getEndDate() : dStart;
                int deleted = studyEventMapper.deleteByTitleInRange(userId, event.getTitle(), dStart, dEnd);
                if (deleted > 0) {
                    log.info("用户{} 替换旧事件: title={}, 删除了{}条", userId, event.getTitle(), deleted);
                }
                studyEventMapper.insert(event);
                saved++;
            }
            log.info("用户{} 手动导入日历事件{}条", userId, saved);
            return saved;
        } catch (Exception e) {
            log.error("用户{} 导入日历事件失败", userId, e);
            throw new RuntimeException("导入失败: " + e.getMessage());
        }
    }

    /**
     * 用 AI（大模型）从文本中提取日程事件，失败返回空列表。
     * 正则先行未命中（<3条）时作为兜底调用，仅处理自由文本中的明确日期。
     */
    private List<StudyEvent> aiExtractEvents(Long userId, String text) {
        try {
            String prompt = String.format(EVENT_EXTRACT_PROMPT, LocalDate.now(), LocalDate.now());
            String userText = text.substring(0, Math.min(text.length(), 2000));

            // 10秒超时，AI 仅兜底用，避免长时间阻塞导入
            String result = CompletableFuture.supplyAsync(() ->
                    chatModel.chat(ChatRequest.builder()
                            .messages(List.of(SystemMessage.from(prompt), UserMessage.from(userText)))
                            .build())
                            .aiMessage().text()
            ).get(10, TimeUnit.SECONDS);

            if (result == null || result.isBlank()) return Collections.emptyList();

            // 清洗 LLM 可能包裹的 markdown 代码块
            result = result.trim();
            if (result.startsWith("```")) {
                result = result.replaceAll("```json\\s*", "").replaceAll("```\\s*", "");
            }

            List<Map<String, Object>> rawList = objectMapper.readValue(
                    result, new TypeReference<List<Map<String, Object>>>() {});
            List<StudyEvent> events = new ArrayList<>();
            for (Map<String, Object> item : rawList) {
                String title = (String) item.get("title");
                if (title == null || title.isBlank() || title.length() < 2) continue;
                if (isNoiseTitle(title)) continue;

                String dateStr = (String) item.get("date");
                LocalDate date = parseFlexibleDate(dateStr);
                // AI 返回的日期解析不了 → 跳过该条，不影响其余条目
                if (date == null) continue;
                String endDateStr = (String) item.get("endDate");
                LocalDate endDate = endDateStr != null ? parseFlexibleDate(endDateStr) : date;
                if (endDate == null || endDate.isBefore(date)) endDate = date;
                String type = item.get("type") instanceof String s ? s : "task";
                // 复习类型固定紫色，考试类型固定红色（与手动/AI添加保持一致）
                String color = "review".equals(type) ? "#9B59B6" : "exam".equals(type) ? "#F56C6C"
                        : pickColor(title, "plan".equals(type) ? PLAN_COLORS : TASK_COLORS);
                events.add(buildEvent(userId, title, date, endDate, type, color));
            }

            log.info("AI提取事件: userId={}, 提取到{}条", userId, events.size());
            return events;
        } catch (TimeoutException e) {
            log.warn("AI提取事件超时(>10s)，放弃AI兜底");
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("AI提取事件失败，降级到正则: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 宽松解析 AI 可能输出的各种日期格式（yyyy-MM-dd / yyyy/M/d / yyyy年M月d日 / M.d / M月d日），
     * 无年份时走 resolveDate 当年/明年推断。解析失败返回 null。
     */
    private LocalDate parseFlexibleDate(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        try {
            // 含完整年份：yyyy-MM-dd / yyyy/M/d / yyyy年M月d日
            java.util.regex.Matcher mFull = Pattern.compile(
                    "^(\\d{4})\\s*[-年/.月]\\s*(\\d{1,2})\\s*[-日/.月]\\s*(\\d{1,2})\\s*[日]?$").matcher(s);
            if (mFull.find()) {
                return LocalDate.of(Integer.parseInt(mFull.group(1)),
                        Integer.parseInt(mFull.group(2)), Integer.parseInt(mFull.group(3)));
            }
            // 仅月日：M.d / M月d日 / MM-dd
            java.util.regex.Matcher mShort = Pattern.compile(
                    "^(\\d{1,2})\\s*[.月/-]\\s*(\\d{1,2})\\s*[日]?$").matcher(s);
            if (mShort.find()) {
                return resolveDate(Integer.parseInt(mShort.group(1)), Integer.parseInt(mShort.group(2)));
            }
        } catch (Exception e) {
            // 非法日期（如 2月30日），返回 null 跳过该条
        }
        return null;
    }

    private List<StudyEvent> parsePlanPhases(Long userId, String text) {
        List<StudyEvent> events = new ArrayList<>();
        if (text == null || text.isBlank()) return events;

        // 预处理：归一化换行符（\r\n → \n），避免 Windows 换行干扰后续正则
        text = text.replace("\r\n", "\n").replace('\r', '\n');

        // 预处理：移除围栏代码块（```...```），其中的伪代码/示例调用（如 addEvent(...)）不参与事件提取
        text = text.replaceAll("(?s)```.*?```", "");
        text = text.replaceAll("(?m)^\\s*```.*$", "");

        // 预处理：清洗 Markdown 格式（**bold**、#### heading 等）
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", "$1");  // **bold** → bold
        text = text.replaceAll("__(.+?)__", "$1");          // __bold__
        text = text.replaceAll("(?m)^#{1,4}\\s*", "");      // #### heading
        text = text.replaceAll("`(.+?)`", "$1");            // `code`
        text = text.replaceAll("<[^>]+>", "");              // 去掉HTML标签 <br> <p>

        // 预处理：相对日期 → 绝对日期
        LocalDate today = LocalDate.now();
        text = text.replace("今天", (today.getMonthValue() + "." + today.getDayOfMonth()));
        text = text.replace("明天", (today.plusDays(1).getMonthValue() + "." + today.plusDays(1).getDayOfMonth()));
        text = text.replace("后天", (today.plusDays(2).getMonthValue() + "." + today.plusDays(2).getDayOfMonth()));

        // 预处理：归一化 ISO 日期（2026-07-30 → 7.30、2026-08-06 → 8.6），去除前导零，使现有模式可匹配
        text = text.replaceAll("\\b\\d{4}[-/](0?(?:[1-9]|1[0-2]))[-/](0?[1-9]|[12]\\d|3[01])\\b", "$1.$2");
        // 预处理：中文年月日（2026年7月31日 → 7.31）
        text = text.replaceAll("\\d{4}年(0?(?:[1-9]|1[0-2]))月(0?[1-9]|[12]\\d|3[01])日", "$1.$2");

        log.info("parsePlanPhases 预处理后文本(前200字): {}", text.substring(0, Math.min(200, text.length())));

        // 模式1：XX阶段[（(]?M.D-M.D[）)]?  兼容括号/冒号/markdown标记
        // (?<![\u4e00-\u9fa5]) 防止前导汉字泄漏（"分为基础阶段" → "基础阶段"）
        Pattern p1 = Pattern.compile(
                "(?<![\\u4e00-\\u9fa5])" +
                "(?:[*_]{0,2})?([\\u4e00-\\u9fa5]{2,6}(?:阶段|期|轮))(?:[*_]{0,2})?" +
                "[\\s：:，,*\\-|]*[（(]?" +
                "[^)]{0,12}?(\\d{1,2})\\s*[.月/]\\s*(\\d{1,2})\\s*[日]?" +
                "\\s*[-—~至到]\\s*" +
                "(\\d{1,2})\\s*[.月/]\\s*(\\d{1,2})\\s*[日]?" +
                "[）)]?"
        );
        Matcher m1 = p1.matcher(text);
        while (m1.find()) {
            String phase = m1.group(1);
            int startMonth = Integer.parseInt(m1.group(2));
            int startDay = Integer.parseInt(m1.group(3));
            int endMonth = Integer.parseInt(m1.group(4));
            int endDay = Integer.parseInt(m1.group(5));
            LocalDate start = resolveDate(startMonth, startDay);
            LocalDate end = resolveDate(endMonth, endDay);
            events.add(buildEvent(userId, phase, start, end, "plan", pickColor(phase, PLAN_COLORS)));
            log.info("p1匹配: title={}, {}.{} — {}.{}", phase, startMonth, startDay, endMonth, endDay);
        }

        // 模式1c：阶段名称 + 月-月（无具体日，如 基础阶段(8月-9月)、冲刺阶段(11月-12月)）
        // (?<![\u4e00-\u9fa5]) 防止前导汉字泄漏
        Pattern p1c = Pattern.compile(
                "(?<![\\u4e00-\\u9fa5])" +
                "([\\u4e00-\\u9fa5]{2,6}(?:阶段|期|轮))" +
                "[\\s：:，,*\\-|]*[（(]?" +
                "(?:\\d{4}年)?" +                              // 可选年份（2026年）
                "(\\d{1,2})\\s*月" +
                "\\s*[-—~至到]\\s*" +
                "(\\d{1,2})\\s*月" +
                "[）)]?"
        );
        Matcher m1c = p1c.matcher(text);
        while (m1c.find()) {
            String phase = m1c.group(1);
            int startMonth = Integer.parseInt(m1c.group(2));
            int endMonth = Integer.parseInt(m1c.group(3));
            LocalDate start = resolveDate(startMonth, 1);
            // 月末：取该月最后一天
            LocalDate end = resolveDate(endMonth, 1).withDayOfMonth(
                    resolveDate(endMonth, 1).lengthOfMonth());
            events.add(buildEvent(userId, phase, start, end, "plan", pickColor(phase, PLAN_COLORS)));
            log.info("p1c月-月匹配: title={}, {}月 — {}月", phase, startMonth, endMonth);
        }

        // 模式4：表格格式 — 兼容2列（|日期|标题|）和3列（|日期|标题|描述|）
        // 标题非贪婪 + 描述要求至少1字符，避免2列表格的描述吃掉闭合管道
        Pattern p4 = Pattern.compile(
                "\\|\\s*" +
                "(\\d{1,2})\\s*[.月/]\\s*(\\d{1,2})\\s*[日]?" +
                "\\s*[-—~至到]\\s*" +
                "(\\d{1,2})\\s*[.月/]\\s*(\\d{1,2})\\s*[日]?" +
                "\\s*\\|\\s*" +
                "([^|\\n]{2,80}?)" +                         // group 5: 标题（非贪婪，最大80字）
                "(?:\\s*\\|\\s*([^|\\n]{1,200}))?" +         // group 6: 描述（可选，至少1字）
                "\\s*\\|"                                      // 闭合管道
        );
        Matcher m4 = p4.matcher(text);
        while (m4.find()) {
            String title = m4.group(5).trim().replaceAll("^[\\s*•\\-]+", "").trim();
            if (title.length() < 2) continue;
            // 清洗标题中的HTML标签
            title = title.replaceAll("<[^>]+>", "").trim();
            // 清洗描述：去HTML、压缩空白、去首尾标点
            String desc = m4.group(6) != null ? m4.group(6).trim() : null;
            if (desc != null) {
                desc = desc.replaceAll("<[^>]+>", "").replaceAll("[ \\t]{2,}", " ");
                desc = desc.replaceAll("^[：:，,、。（(）) \\t]+", "").trim();
                desc = desc.replaceAll("[，,、。（(）) \\t]+$", "").trim();
                if (desc.isEmpty()) desc = null;
            }
            int startMonth = Integer.parseInt(m4.group(1));
            int startDay = Integer.parseInt(m4.group(2));
            int endMonth = Integer.parseInt(m4.group(3));
            int endDay = Integer.parseInt(m4.group(4));
            LocalDate start = resolveDate(startMonth, startDay);
            LocalDate end = resolveDate(endMonth, endDay);
            events.add(buildEvent(userId, title, start, end, "task", pickColor(title, TASK_COLORS), desc));
            log.info("p4匹配: title={}, {}.{} — {}.{}", title, startMonth, startDay, endMonth, endDay);
        }
        // 模式4b：表格单日行（AI 常见输出格式） — | 日期 | 标题 | 类型 |
        // 如：| 8.5 | Python语法精练 | 学习任务 |
        Pattern p4b = Pattern.compile(
                "\\|\\s*" +
                "(\\d{1,2})\\s*[.月/]\\s*(\\d{1,2})\\s*[日]?" +
                "\\s*\\|\\s*" +
                "([^|\\n]{2,80})" +                           // group 3: 标题（贪婪，遇 | 或换行即停）
                "\\s*\\|\\s*" +
                "([^|\\n]{0,30})" +                           // group 4: 类型
                "\\s*\\|"
        );
        Matcher m4b = p4b.matcher(text);
        while (m4b.find()) {
            String title = m4b.group(3).trim();
            if (title.length() < 2) continue;
            if (isNoiseTitle(title)) continue;
            title = title.replaceAll("<[^>]+>", "").trim();
            int month = Integer.parseInt(m4b.group(1));
            int day = Integer.parseInt(m4b.group(2));
            LocalDate date = resolveDate(month, day);
            String typeLabel = m4b.group(4).trim();
            List<String> palette = "plan".equals(typeLabel) ? PLAN_COLORS : TASK_COLORS;
            events.add(buildEvent(userId, title, date, date, "task", pickColor(title, palette)));
            log.info("p4b单日表格匹配: title={}, date={}.{}", title, month, day);
        }

        // 模式7：学习计划日偏移表格 — | D{n} | 内容 | 内容 | ... |
        // 如：| D1 | OSI七层模型 vs TCP/IP四层 | 用Wireshark抓取本地HTTP流量 |
        // D1 = 今天, D2 = 明天, 自动推算日期
        // 先收集到临时列表，少于5条则丢弃（交给AI兜底）
        Pattern p7 = Pattern.compile(
                "\\|\\s*D\\s*(\\d{1,2})\\s*\\|\\s*" +
                "([^|\\n]{2,80})\\s*\\|\\s*" +
                "([^|\\n]{2,80})"
        );
        Matcher m7 = p7.matcher(text);
        List<StudyEvent> p7Events = new ArrayList<>();
        if (m7.find()) {
            String subject = extractPlanSubject(text);
            String prefix = subject != null ? subject + " Day" : "Day";
            m7.reset();
            while (m7.find()) {
                int dayNum = Integer.parseInt(m7.group(1));
                String morning = m7.group(2).trim().replaceAll("^[\\s*•\\-]+", "").trim();
                String evening = m7.group(3).trim().replaceAll("^[\\s*•\\-]+", "").trim();
                if (morning.length() < 2 && evening.length() < 2) continue;

                LocalDate date = today.plusDays(dayNum - 1);
                String title = prefix + dayNum + ": " + morning;
                if (evening.length() >= 2) {
                    title += " / " + evening;
                }
                if (title.length() > 35) {
                    title = title.substring(0, 32) + "...";
                }
                p7Events.add(buildEvent(userId, title, date, date, "task", pickColor(title, TASK_COLORS)));
            }
            // 正则能匹配绝大部分行（≥5条）才采纳，否则交给 AI 兜底
            if (p7Events.size() >= 5) {
                events.addAll(p7Events);
                log.info("p7日偏移匹配: {}条", p7Events.size());
            } else {
                log.info("p7仅匹配{}条（<5），丢弃正则结果，交AI兜底", p7Events.size());
            }
        }

        // 匹配：7.29-7.30复习数据结构、8.1-8.5完成作业
        // [ \\t] 仅匹配水平空白，不跨行，避免误匹配换行后的无关文本
        // 跳过阶段名称（阶段/期/轮），由 p1 专门处理，避免冲突
        Pattern p5b = Pattern.compile(
                "(\\d{1,2})\\s*[.月/]\\s*(\\d{1,2})\\s*[日]?" +
                "\\s*[-—~至到]\\s*" +
                "(\\d{1,2})\\s*[.月/]\\s*(\\d{1,2})\\s*[日]?" +
                "[ \\t]*" +
                "([\\u4e00-\\u9fa5a-zA-Z0-9]{2,20})"
        );
        Matcher m5b = p5b.matcher(text);
        while (m5b.find()) {
            String title = m5b.group(5).trim();
            if (title.contains("阶段") || title.contains("期") || title.contains("轮")) continue;
            if (isNoiseTitle(title)) continue;
            int startMonth = Integer.parseInt(m5b.group(1));
            int startDay = Integer.parseInt(m5b.group(2));
            int endMonth = Integer.parseInt(m5b.group(3));
            int endDay = Integer.parseInt(m5b.group(4));
            LocalDate start = resolveDate(startMonth, startDay);
            LocalDate end = resolveDate(endMonth, endDay);
            events.add(buildEvent(userId, title, start, end, "task",
                    pickColor(title, TASK_COLORS), tailDesc(text, m5b.end())));
        }

        // 模式1b：纯日期范围，只在日期完全孤立时匹配（前后都是空白/句尾）
        // 不匹配表格行内、括号内、标题中的日期
        // 前置断言 (?<![一-龥] ) 排除"时间范围为 8月1日"这类散文：中文标签 + 空格 + 日期的组合
        Pattern p1b = Pattern.compile(
                "(?<![^\\s])" +                               // 前面是空白（或文本开头）
                "(?<![一-龥] )" +                             // 前两个字符不是"中文+空格"（散文标签粘连）
                "(?<!\\|\\s{0,3})" +                          // 不在表格行内（| 后多空格）
                "(\\d{1,2})\\s*[.月/]\\s*(\\d{1,2})\\s*[日]?" +
                "\\s*[-—~至到]\\s*" +
                "(\\d{1,2})\\s*[.月/]\\s*(\\d{1,2})\\s*[日]?" +
                "(?=\\s|$|[）)]|[。！？，,])"                    // 后面是空白、句尾、括号或标点
        );
        Matcher m1b = p1b.matcher(text);
        while (m1b.find()) {
            int startMonth = Integer.parseInt(m1b.group(1));
            int startDay = Integer.parseInt(m1b.group(2));
            int endMonth = Integer.parseInt(m1b.group(3));
            int endDay = Integer.parseInt(m1b.group(4));

            LocalDate start = resolveDate(startMonth, startDay);
            LocalDate end = resolveDate(endMonth, endDay);

            events.add(buildEvent(userId, "学习计划", start, end, "plan", pickColor("学习计划", PLAN_COLORS)));
        }

        // 模式2：XXXX-XX-XX 至 XXXX-XX-XX XX阶段
        Pattern p2 = Pattern.compile(
                "(\\d{4})-(\\d{1,2})-(\\d{1,2})\\s*[-—~至]\\s*" +
                "(\\d{4})-(\\d{1,2})-(\\d{1,2})\\s*[，,]?\\s*([\\u4e00-\\u9fa5]{2,6}(?:阶段|期|轮))?"
        );
        Matcher m2 = p2.matcher(text);
        while (m2.find()) {
            LocalDate start = LocalDate.of(
                    Integer.parseInt(m2.group(1)), Integer.parseInt(m2.group(2)), Integer.parseInt(m2.group(3)));
            LocalDate end = LocalDate.of(
                    Integer.parseInt(m2.group(4)), Integer.parseInt(m2.group(5)), Integer.parseInt(m2.group(6)));
            String phase = m2.group(7) != null ? m2.group(7) : "学习计划";

            events.add(buildEvent(userId, phase, start, end, "plan", pickColor(phase, PLAN_COLORS)));
        }

        // 模式3：子任务 — 日期范围 + 分隔符 + 标题
        // 匹配：第1周（8.1-8.7）：高等数学、8.1-8.7：高等数学、8月1日-7日——高等数学
        Pattern p3 = Pattern.compile(
                "(?:第\\s*\\d+\\s*周\\s*[：:]*\\s*[（(]?)?" +   // 可选「第X周（」
                "(\\d{1,2})\\s*[.月/]\\s*(\\d{1,2})\\s*[日]?" +
                "\\s*[-—~至到]\\s*" +
                "(\\d{1,2})\\s*[.月/]\\s*(\\d{1,2})\\s*[日]?" +
                "\\s*[）)]?\\s*[：:—\\-]+?\\s*" +   // 允许（8.1-8.7）后面的闭括号
                "([^\\n,]{2,40})"                                // 标题（含中文逗号，不截断）
        );
        Matcher m3 = p3.matcher(text);
        while (m3.find()) {
            String matched = m3.group(0);
            String title = m3.group(5).trim().replaceAll("^[\\s*•\\-|]+", "").trim();
            if (title.length() < 2) continue;
            if (title.contains("阶段") || title.contains("期") || title.contains("轮")) continue;
            if (isNoiseTitle(title)) continue;

            int startMonth = Integer.parseInt(m3.group(1));
            int startDay = Integer.parseInt(m3.group(2));
            int endMonth = Integer.parseInt(m3.group(3));
            int endDay = Integer.parseInt(m3.group(4));

            LocalDate start = resolveDate(startMonth, startDay);
            LocalDate end = resolveDate(endMonth, endDay);

            events.add(buildEvent(userId, title, start, end, "task",
                    pickColor(title, TASK_COLORS), tailDesc(text, m3.end())));
        }

        // 模式5：自由标题 + 日期范围（非阶段/期/轮结尾的通用标题）
        // 匹配：复习数据结构 7.29-7.30、完成作业 8.1-8.5
        // 跳过阶段名称，由 p1 专门处理，避免创建重复的 task 类型事件
        Pattern p5 = Pattern.compile(
                "(?<![\\d.月日])" +
                "([\\u4e00-\\u9fa5a-zA-Z0-9]{2,20})\\s+" +
                "(\\d{1,2})\\s*[.月/]\\s*(\\d{1,2})\\s*[日]?" +
                "\\s*[-—~至到]\\s*" +
                "(\\d{1,2})\\s*[.月/]\\s*(\\d{1,2})\\s*[日]?"
        );
        Matcher m5 = p5.matcher(text);
        while (m5.find()) {
            String title = m5.group(1).trim();
            if (title.contains("阶段") || title.contains("期") || title.contains("轮")) continue;
            if (isNoiseTitle(title)) continue;
            int startMonth = Integer.parseInt(m5.group(2));
            int startDay = Integer.parseInt(m5.group(3));
            int endMonth = Integer.parseInt(m5.group(4));
            int endDay = Integer.parseInt(m5.group(5));
            LocalDate start = resolveDate(startMonth, startDay);
            LocalDate end = resolveDate(endMonth, endDay);
            events.add(buildEvent(userId, title, start, end, "task",
                    pickColor(title, TASK_COLORS), tailDesc(text, m5.end())));
        }

        // 模式6：单日 + 冒号 + 标题
        // 匹配：8月3日：Spring Boot入门、8月4日：学习Spring Boot数据库集成
        // 使用惰性匹配+前瞻边界，防止无换行多任务文本中贪婪吃掉下一条任务
        Pattern p6 = Pattern.compile(
                "(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日" +
                "\\s*[：:]\\s*" +
                "(.{2,50}?)" +
                "(?=\\s*\\d+月\\d+日|\\s*$|\\n|\\r)"
        );
        Matcher m6 = p6.matcher(text);
        while (m6.find()) {
            String title = m6.group(3).trim();
            if (title.length() < 2) continue;
            int month = Integer.parseInt(m6.group(1));
            int day = Integer.parseInt(m6.group(2));
            LocalDate date = resolveDate(month, day);
            events.add(buildEvent(userId, title, date, date, "task",
                    pickColor(title, TASK_COLORS), tailDesc(text, m6.end())));
        }

        // 模式8：日期附近出现引号/括号包裹的标题（如 「运动」、"运动"、"运动"、'运动'）
        // 匹配：7.30的日程中添加了"运动"任务  →  标题=运动, 日期=7.30
        Pattern p8 = Pattern.compile(
                "(\\d{1,2})\\s*[.月/]\\s*(\\d{1,2})\\s*[日]?" +
                "[\\s\\S]{0,20}?" +                          // 中间可能有描述文字
                "[\u201c\u2018「『\"']([\\u4e00-\\u9fa5a-zA-Z0-9 ]{2,20})[\u201d\u2019」』\"']"
        );
        Matcher m8 = p8.matcher(text);
        while (m8.find()) {
            String title = m8.group(3).trim();
            if (title.length() < 2) continue;
            if (isNoiseTitle(title)) continue;
            int month = Integer.parseInt(m8.group(1));
            int day = Integer.parseInt(m8.group(2));
            LocalDate date = resolveDate(month, day);
            events.add(buildEvent(userId, title, date, date, "task",
                    pickColor(title, TASK_COLORS)));
            log.info("p8匹配: title={}, date={}.{}", title, month, day);
        }

        // 模式9：引号标题在前，日期在后（如 "运动"任务添加到7.31的日历中）
        Pattern p9 = Pattern.compile(
                "[\u201c\u2018「『\"']([\\u4e00-\\u9fa5a-zA-Z0-9 ]{2,20})[\u201d\u2019」』\"']" +
                "[\\s\\S]{0,50}?" +                          // 中间描述文字
                "(\\d{1,2})\\s*[.月/]\\s*(\\d{1,2})\\s*[日]?"
        );
        Matcher m9 = p9.matcher(text);
        while (m9.find()) {
            String title = m9.group(1).trim();
            if (title.length() < 2) continue;
            if (isNoiseTitle(title)) continue;
            int month = Integer.parseInt(m9.group(2));
            int day = Integer.parseInt(m9.group(3));
            LocalDate date = resolveDate(month, day);
            events.add(buildEvent(userId, title, date, date, "task",
                    pickColor(title, TASK_COLORS)));
            log.info("p9匹配: title={}, date={}.{}", title, month, day);
        }

        // 模式7：Markdown表格单日行 — 已禁用，子任务不单独显示
        // 子任务内容应由父事件（p5b/p4）的 tailDesc 捕获到描述中
        // Pattern p7 = Pattern.compile( ... );
        // see parsePlanPhases tailDesc for sub-task capture

        // 模式10+11：相对天数表格（| 1-2 | 内容 | ... |）与阶段标题（第X-Y天）
        events.addAll(parseDayOffsetTables(userId, text, today));

        // 模式12+13：相对周范围计划（阶段1：基础巩固（1-2周）+ 阶段下子任务继承日期）
        events.addAll(parseWeekOffsetPlans(userId, text, today));

        // 模式16：周表格 — | 第X周 | 学习内容 | 任务 |
        events.addAll(parseWeekTable(userId, text, today));

        // 模式17：每周大标题 — 第X周：标题（8月1日-8月7日）
        events.addAll(parseWeeklyHeadings(userId, text, today));

        // 模式14：今日计划 — 今日学习计划（8.5）+ 编号任务 + 时间/内容行（9:00 - 10:30）
        events.addAll(parseTodayPlan(userId, text, today));

        // 模式15：时钟时间表格 — | 09:00-09:30 | 任务内容 | 类型 | ...（无日期列，取标题日期/默认今天）
        events.addAll(parseClockTable(userId, text, today));

        // 安全过滤：剔除明显是对话碎片而非计划标题的条目
        events.removeIf(e -> e.getTitle() != null && isNoiseTitle(e.getTitle()));

        log.info("去重前事件数: {}, 明细: {}", events.size(),
                events.stream().map(e -> e.getTitle() + "(" + e.getEventType() + ":" + e.getEventDate() + "~" + e.getEndDate() + ")")
                        .collect(java.util.stream.Collectors.joining(", ")));

        // 按日期范围去重：同一日期范围只保留非通用标题的（「学习计划」让位给「基础阶段」等）
        List<StudyEvent> result = dedupByDateRange(events);

        log.info("去重后事件数: {}, 明细: {}", result.size(),
                result.stream().map(e -> e.getTitle() + "(" + e.getEventType() + ":" + e.getEventDate() + "~" + e.getEndDate() + ")")
                        .collect(java.util.stream.Collectors.joining(", ")));

        return result;
    }

    /**
     * 解析"相对天数"格式的学习计划（如 30天数据结构训练计划）：
     * 1) 模式10：阶段表格 — | 1-2 | 数组 | 学习重点 | 实战 | ...，第一列为纯数字（可带范围），1=今天
     * 2) 模式11：阶段标题 — 基础构建阶段（第1-7天）
     * 返回空列表表示文本中没有此格式。
     */
    private List<StudyEvent> parseDayOffsetTables(Long userId, String text, LocalDate today) {
        List<StudyEvent> events = new ArrayList<>();
        if (text == null || text.isBlank()) return events;

        // 模式10：相对天数表格 — | 1-2 | 数组 | 遍历/二分/双指针 | #27 #35 #704 |
        // 第一列为纯数字天（可带范围），标题列需含中文，序号严格递增（学习计划表格的天然特征）
        Pattern p10 = Pattern.compile(
                "\\|\\s*(\\d{1,2})\\s*(?:[-—~至到]\\s*(\\d{1,2}))?\\s*\\|\\s*" +
                "([\\u4e00-\\u9fa5a-zA-Z0-9#/\\-]{2,40})\\s*\\|\\s*" +
                "([^|\\n]{1,120})"
        );
        Matcher m10 = p10.matcher(text);
        List<StudyEvent> tableEvents = new ArrayList<>();
        int lastDayNum = -1;
        while (m10.find()) {
            int dayNum = Integer.parseInt(m10.group(1));
            int dayEnd = m10.group(2) != null ? Integer.parseInt(m10.group(2)) : dayNum;
            String title = m10.group(3).trim();
            if (title.length() < 2) continue;
            // 标题需含中文，避免把纯数字/英文表格误当学习计划
            if (!title.matches(".*[\\u4e00-\\u9fa5].*")) continue;
            // 天数应严格递增
            if (dayNum <= lastDayNum) continue;
            lastDayNum = dayNum;
            String desc = m10.group(4).trim().replaceAll("^[\\s*•\\-]+", "").trim();
            LocalDate start = today.plusDays(dayNum - 1);
            LocalDate end = today.plusDays(dayEnd - 1);
            tableEvents.add(buildEvent(userId, title, start, end, "task",
                    pickColor(title, TASK_COLORS), desc));
            log.info("p10相对天数表格匹配: title={}, Day{}-Day{}", title, dayNum, dayEnd);
        }
        // ≥3 条才采纳，避免单行/碎表格误判
        if (tableEvents.size() >= 3) {
            events.addAll(tableEvents);
            log.info("p10相对天数表格采纳{}条", tableEvents.size());
        } else {
            log.info("p10仅匹配{}条（<3），不采纳", tableEvents.size());
        }

        // 模式11：阶段标题带相对天数 — 「基础构建阶段（第1-7天）」「综合实战阶段（第22-30天）」
        Pattern p11 = Pattern.compile(
                "([\\u4e00-\\u9fa5]{2,8}(?:阶段|期|轮))" +
                "\\s*[（(]?\\s*第\\s*(\\d{1,2})\\s*[-—~至到]\\s*(\\d{1,2})\\s*天\\s*[）)]?"
        );
        Matcher m11 = p11.matcher(text);
        while (m11.find()) {
            String phase = m11.group(1);
            int dayStart = Integer.parseInt(m11.group(2));
            int dayEnd = Integer.parseInt(m11.group(3));
            LocalDate start = today.plusDays(dayStart - 1);
            LocalDate end = today.plusDays(dayEnd - 1);
            events.add(buildEvent(userId, phase, start, end, "plan",
                    pickColor(phase, PLAN_COLORS)));
            log.info("p11阶段标题匹配: title={}, Day{}-Day{}", phase, dayStart, dayEnd);
        }
        return events;
    }

    /**
     * 解析"相对周范围"学习计划 — 阶段名（X-Y周），以今天为第1周起点：
     *   例如「阶段1：基础巩固（1-2周）」→ 第1周~第2周 = 今天 ~ 今天+13天（plan 事件）
     *   阶段标题下的 "- xxx" 列表项作为子任务，继承阶段的日期范围（task 事件）。
     * 返回空列表表示文本中没有此格式。
     */
    private List<StudyEvent> parseWeekOffsetPlans(Long userId, String text, LocalDate today) {
        List<StudyEvent> events = new ArrayList<>();
        if (text == null || text.isBlank()) return events;

        // 模式12：阶段名（第X-Y周）— 如 阶段1：基础巩固（1-2周）、基础巩固阶段（1-2周）
        Pattern p12 = Pattern.compile(
                "([\\u4e00-\\u9fa5a-zA-Z0-9：:、，,]{2,16}?)" +   // 阶段名（非贪婪）
                "[\\s（(\\[]*" +
                "(?:第\\s*)?(\\d{1,2})\\s*[-—~至到]\\s*(\\d{1,2})\\s*周"
        );
        Matcher m12 = p12.matcher(text);
        List<int[]> ranges = new ArrayList<>();
        List<StudyEvent> phaseEvents = new ArrayList<>();
        while (m12.find()) {
            String name = m12.group(1).trim();
            // 去掉"一、基础阶段"这类序号前缀
            name = name.replaceAll("^[一二三四五六七八九十]+[、.．]\\s*", "");
            // 名称需像阶段名（含阶段/模块，或以期/轮结尾），避免误匹配"坚持1-2周"这类散文
            if (!name.contains("阶段") && !name.contains("模块")
                    && !name.endsWith("期") && !name.endsWith("轮")) continue;
            int weekStart = Integer.parseInt(m12.group(2));
            int weekEnd = Integer.parseInt(m12.group(3));
            if (weekStart < 1 || weekEnd < weekStart || weekEnd > 52) continue;

            ranges.add(new int[]{m12.start(), m12.end()});
            LocalDate start = today.plusDays((long) (weekStart - 1) * 7);
            LocalDate end = today.plusDays((long) weekEnd * 7 - 1);
            phaseEvents.add(buildEvent(userId, name, start, end, "plan", pickColor(name, PLAN_COLORS)));
            log.info("p12周偏移阶段匹配: title={}, 第{}周-第{}周 => {} ~ {}", name, weekStart, weekEnd, start, end);
        }
        if (phaseEvents.isEmpty()) return events;
        events.addAll(phaseEvents);

        // 模式13：阶段标题到下一阶段标题/分隔线之间的 "- xxx" 列表项，整合为阶段描述
        // （阶段名作为事件标题，重点/里程碑等子内容合并进描述，而不是拆成多个独立任务）
        Pattern item = Pattern.compile("(?m)^[ \\t]*[-*•▪◦][ \\t]+([^\\n]{2,120})");
        for (int i = 0; i < phaseEvents.size(); i++) {
            int segStart = ranges.get(i)[1];
            int segEnd = (i + 1 < ranges.size()) ? ranges.get(i + 1)[0] : text.length();
            String segment = text.substring(segStart, segEnd);
            // 分隔线（--- / ***）或中文序号标题（四、XXX）之后的内容不再属于该阶段（如"资源推荐""备考建议"区块）
            Matcher sep = Pattern.compile(
                    "(?m)^[ \\t]*(?:-{3,}|\\*{3,}|_{3,}|[一二三四五六七八九十]+[、.．][^\\n]{0,30})[ \\t]*$"
            ).matcher(segment);
            if (sep.find()) segment = segment.substring(0, sep.start());

            Matcher mi = item.matcher(segment);
            List<String> descItems = new ArrayList<>();
            while (mi.find()) {
                String line = mi.group(1).trim();
                line = line.replaceAll("https?://\\S+", "");       // 去掉链接
                line = line.replaceAll("[ \\t]{2,}", " ");          // 折叠多余空格
                line = line.replaceAll("[（(]如[\\s]*[）)]", "");    // 去掉链接残留的"（如  ）"
                line = line.replaceAll("[（(][\\s]*[）)]", "");      // 去掉空括号
                line = line.replaceAll("[，,、；;。.\\s]+$", "").trim(); // 去尾部标点/空白
                if (line.length() < 2) continue;
                if (line.endsWith("示例")) continue;                 // 跳过"每日任务示例"等区块标签
                if (isNoiseTitle(line)) continue;
                descItems.add(line);
            }
            if (!descItems.isEmpty()) {
                String desc = String.join("；", descItems);
                if (desc.length() > 200) desc = desc.substring(0, 197) + "...";
                phaseEvents.get(i).setDescription(desc);
                log.info("p12阶段[{}] 整合描述{}条", phaseEvents.get(i).getTitle(), descItems.size());
            }
        }
        return events;
    }

    /**
     * 解析"周表格"学习计划 — | 第X周 | 学习内容 | 任务 |：
     *   例如「| 第1周 | OSI模型、TCP/IP模型 | 阅读教材，完成OSI模型对比练习 |」
     *   第X周日期 = 今天 + (X-1)*7 ~ 今天 + X*7 - 1（与 p12 周偏移口径一致）。
     * 返回空列表表示文本中没有此格式。
     */
    private List<StudyEvent> parseWeekTable(Long userId, String text, LocalDate today) {
        List<StudyEvent> events = new ArrayList<>();
        if (text == null || text.isBlank()) return events;

        Pattern p16 = Pattern.compile(
                "\\|\\s*第\\s*(\\d{1,2})\\s*周\\s*\\|\\s*" +
                "([^|\\n]{2,60}?)\\s*\\|\\s*" +
                "([^|\\n]{0,120})"
        );
        Matcher m16 = p16.matcher(text);
        List<StudyEvent> tableEvents = new ArrayList<>();
        int lastWeek = -1;
        while (m16.find()) {
            int weekNum = Integer.parseInt(m16.group(1));
            if (weekNum < 1 || weekNum > 52) continue;
            // 周次应严格递增（学习计划表格的天然特征），防碎表格/跨主题误判
            if (weekNum <= lastWeek) continue;
            lastWeek = weekNum;
            String content = m16.group(2).trim().replaceAll("^[\\s*•\\-]+", "").trim();
            if (content.length() < 2) continue;
            // 内容需含中文或英文字母，避免把纯数字/符号表格误当学习计划（如"VLAN、VPN、NAT"是合法内容）
            if (!content.matches(".*[\\u4e00-\\u9fa5a-zA-Z].*")) continue;
            String task = m16.group(3).trim().replaceAll("^[\\s*•\\-]+", "").trim();
            if (task.isEmpty()) continue;
            String title = "第" + weekNum + "周：" + content;
            LocalDate start = today.plusDays((long) (weekNum - 1) * 7);
            LocalDate end = today.plusDays((long) weekNum * 7 - 1);
            tableEvents.add(buildEvent(userId, title, start, end, "task",
                    pickColor(title, TASK_COLORS), task));
            log.info("p16周表格匹配: title={}, 第{}周 => {} ~ {}", title, weekNum, start, end);
        }
        // ≥3 条才采纳，避免单行/碎表格误判（与 p10 门槛一致）
        if (tableEvents.size() >= 3) {
            events.addAll(tableEvents);
            log.info("p16周表格采纳{}条", tableEvents.size());
        } else {
            log.info("p16仅匹配{}条（<3），不采纳", tableEvents.size());
        }
        return events;
    }

    /**
     * 解析"每周大标题" — 第X周：标题（8月1日-8月7日）：
     *   例如「#### 第一周：线性结构（8月1日-8月7日）」
     *   标题 = 第X周：标题，日期取括号内的明确日期范围，标题下的 "- xxx" 列表项
     *   （学习内容/任务等子要点）合并进描述而不是拆成独立事件。
     * 返回空列表表示文本中没有此格式。
     */
    private List<StudyEvent> parseWeeklyHeadings(Long userId, String text, LocalDate today) {
        List<StudyEvent> events = new ArrayList<>();
        if (text == null || text.isBlank()) return events;

        Pattern p17 = Pattern.compile(
                "第\\s*([一二三四五六七八九十]+|\\d{1,2})\\s*周\\s*[：:]\\s*" +
                "([^（(\\n]{2,40}?)" +
                "\\s*[（(]\\s*" +
                "(\\d{1,2})\\s*[.月/]\\s*(\\d{1,2})\\s*[日]?\\s*[-—~至到]\\s*" +
                "(\\d{1,2})\\s*[.月/]\\s*(\\d{1,2})\\s*[日]?\\s*[）)]"
        );
        Matcher m17 = p17.matcher(text);
        List<StudyEvent> headingEvents = new ArrayList<>();
        List<int[]> ranges = new ArrayList<>();
        while (m17.find()) {
            String weekLabel = m17.group(1);
            String title = m17.group(2).trim();
            if (title.length() < 2) continue;
            LocalDate start;
            LocalDate end;
            try {
                start = resolveDate(Integer.parseInt(m17.group(3)), Integer.parseInt(m17.group(4)));
                end = resolveDate(Integer.parseInt(m17.group(5)), Integer.parseInt(m17.group(6)));
            } catch (Exception e) {
                continue;
            }
            if (end.isBefore(start)) continue;
            String eventTitle = "第" + weekLabel + "周：" + title;
            headingEvents.add(buildEvent(userId, eventTitle, start, end, "plan",
                    pickColor(eventTitle, PLAN_COLORS)));
            ranges.add(new int[]{m17.start(), m17.end()});
            log.info("p17每周大标题匹配: title={}, {} ~ {}", eventTitle, start, end);
        }
        if (headingEvents.isEmpty()) return events;
        events.addAll(headingEvents);

        // 标题下的 "- xxx" 列表项合并为描述（学习内容/任务等子要点），到下一周标题或分隔线前截止
        Pattern item = Pattern.compile("(?m)^[ \\t]*[-*•▪◦][ \\t]+([^\\n]{2,120})");
        for (int i = 0; i < headingEvents.size(); i++) {
            int segStart = ranges.get(i)[1];
            int segEnd = (i + 1 < ranges.size()) ? ranges.get(i + 1)[0] : text.length();
            String segment = text.substring(segStart, segEnd);
            // 分隔线（--- / ***）或中文序号标题（四、XXX）之后的内容不再属于该周（如"每日学习建议""推荐资源"区块）
            Matcher sep = Pattern.compile(
                    "(?m)^[ \\t]*(?:-{3,}|\\*{3,}|_{3,}|[一二三四五六七八九十]+[、.．][^\\n]{0,30})[ \\t]*$"
            ).matcher(segment);
            if (sep.find()) segment = segment.substring(0, sep.start());

            Matcher mi = item.matcher(segment);
            List<String> descItems = new ArrayList<>();
            while (mi.find()) {
                String line = mi.group(1).trim();
                line = line.replaceAll("https?://\\S+", "");       // 去掉链接
                line = line.replaceAll("[ \\t]{2,}", " ");          // 折叠多余空格
                line = line.replaceAll("[（(]如[\\s]*[）)]", "");    // 去掉"（如  ）"残留
                line = line.replaceAll("[（(][\\s]*[）)]", "");      // 去掉空括号
                line = line.replaceAll("[，,、；;。.\\s]+$", "").trim(); // 去尾部标点/空白
                if (line.length() < 2) continue;
                if (line.endsWith("示例")) continue;                 // 跳过"每日任务示例"等区块标签
                if (isNoiseTitle(line)) continue;
                descItems.add(line);
            }
            if (!descItems.isEmpty()) {
                String desc = String.join("；", descItems);
                if (desc.length() > 200) desc = desc.substring(0, 197) + "...";
                headingEvents.get(i).setDescription(desc);
                log.info("p17周[{}] 整合描述{}条", headingEvents.get(i).getTitle(), descItems.size());
            }
        }
        return events;
    }

    /**
     * 解析"今日计划" — 标题带日期（今日学习计划（8.5）），编号任务带时钟时间行与可选内容行：
     *     1. **数据结构与算法分析（薄弱科目）**
     *        - 时间：9:00 - 10:30
     *        - 内容：复习链表的基本操作
     *   所有任务落在标题日期（缺省为今天），时间/内容并入描述。
     * 返回空列表表示文本中没有此格式。
     */
    private List<StudyEvent> parseTodayPlan(Long userId, String text, LocalDate today) {
        List<StudyEvent> events = new ArrayList<>();
        if (text == null || text.isBlank()) return events;

        // 计划日期：优先取标题括号里的日期（今日学习计划（2026-08-05）/（8.5）/（8月5日）），否则默认为今天
        LocalDate planDate = today;
        // 格式1：完整日期 yyyy-MM-dd / yyyy年M月d日 / yyyy/M/d
        Matcher mFull = Pattern.compile(
                "[（(]\\s*(\\d{4})\\s*[-年/.月]\\s*(\\d{1,2})\\s*[-日/.月]\\s*(\\d{1,2})\\s*[日]?\\s*[）)]"
        ).matcher(text);
        if (mFull.find()) {
            planDate = LocalDate.of(Integer.parseInt(mFull.group(1)),
                    Integer.parseInt(mFull.group(2)), Integer.parseInt(mFull.group(3)));
        } else {
            // 格式2：简写日期（今日学习计划（8.5）/ 推荐学习计划（8.6）/ 今天（8.5，周三））
            // 预处理已把 yyyy-MM-dd 归一化为 M.D，故直接匹配括号内的简写日期，不再要求日期在括号前
            Matcher mDate = Pattern.compile(
                    "[（(]\\s*(\\d{1,2})\\s*[.月/]\\s*(\\d{1,2})\\s*[日]?\\s*" +
                    "(?:[，,]\\s*周[一二三四五六日天])?\\s*[）)]"
            ).matcher(text);
            if (mDate.find()) {
                planDate = resolveDate(Integer.parseInt(mDate.group(1)), Integer.parseInt(mDate.group(2)));
            }
        }

        // 编号任务项：标题行 + 可选的 时间行（时钟格式）/ 内容行
        // (?!\d) 防止把"8.5没有安排…"这类日期开头句误当编号（8. → 5…）
        // 兼容 AI 常见变体：**时间建议**：9:00 - 10:30 / **内容**：xxx / **具体任务**： / **目标**：
        Pattern item = Pattern.compile(
                "(?m)^\\s*(\\d{1,2})\\s*[.、．）)]\\s*(?!\\d)\\*{0,2}([^\\n*_]{2,40}?)\\*{0,2}[：:]?\\s*\\n" +
                "(?:\\s*[-•▪◦]\\s*\\*{0,2}(?:时间建议|时间|安排)[：:]\\s*([0-9]{1,2}[:：][0-9]{2}(?:\\s*[-—~至到]\\s*[0-9]{1,2}[:：][0-9]{2})?)\\*{0,2})?" +
                "(?:(?:\\s*\\n)?\\s*[-•▪◦]\\s*\\*{0,2}(?:内容|具体任务|目标|任务)[：:]\\s*([^\\n]{2,120})\\*{0,2})?"
        );
        Matcher mi = item.matcher(text);
        List<StudyEvent> found = new ArrayList<>();
        while (mi.find()) {
            String title = mi.group(2).trim();
            if (title.length() < 2) continue;
            if (isNoiseTitle(title)) continue;
            String time = mi.group(3);
            String content = mi.group(4);
            String desc = null;
            if (time != null && content != null) desc = time + " " + content;
            else if (time != null) desc = time;
            else if (content != null) desc = content;
            if (desc != null && desc.length() > 120) desc = desc.substring(0, 117) + "...";
            found.add(buildEvent(userId, title, planDate, planDate, "task",
                    pickColor(title, TASK_COLORS), desc));
        }

        // 行内时间段任务：- 9:00-10:30：复习链表核心操作（标题带日期/今日语义时任务只带时间段）
        Pattern inlineTime = Pattern.compile(
                "(?m)^\\s*[-•▪◦]\\s*" +
                "([0-9]{1,2}[:：][0-9]{2})\\s*[-—~至到]\\s*([0-9]{1,2}[:：][0-9]{2})" +
                "\\s*[：:]\\s*([^\\n]{2,80})"
        );
        Matcher mit = inlineTime.matcher(text);
        int inlineCount = 0;
        while (mit.find()) {
            String title = mit.group(3).trim();
            if (title.length() < 2) continue;
            if (isNoiseTitle(title)) continue;
            // 去掉"可选：""建议："等标签前缀
            title = title.replaceAll("^(可选|建议)[：:]\\s*", "").trim();
            if (title.length() < 2) continue;
            String time = mit.group(1) + "-" + mit.group(2);
            found.add(buildEvent(userId, title, planDate, planDate, "task",
                    pickColor(title, TASK_COLORS), time));
            inlineCount++;
        }

        // 若已提取到行内时间段任务，则编号标题中"无时间/内容描述"的纯标题事件属于冗余
        //（如"1. 数据结构与算法基础巩固"下的"- 9:00-10:30：复习链表核心操作"），丢弃只保留具体任务。
        // buildEvent 对无描述事件会填默认值"日期 至 日期"，据此识别纯标题事件
        if (inlineCount > 0) {
            found.removeIf(e -> e.getDescription() == null
                    || e.getDescription().matches("\\d{4}-\\d{2}-\\d{2} 至 \\d{4}-\\d{2}-\\d{2}"));
        }

        // 采纳门槛：文本带"今日/今天…计划/推荐/建议/安排"标题特征 或"推荐学习计划"+ 任务≥2 条才采纳；
        // 时间行不再作为必要条件（今日计划可能只有标题+内容行，甚至只有标题行）
        // 注意：预处理已把"今天"替换为"M.d"（如 8.6），故门槛同时兼容"M.d的学习建议"这种替换后形式
        boolean todayPlan = Pattern.compile(
                "今日[的]?(?:学习|复习|预习)?(?:计划|推荐|建议|安排)|" +
                "今天[的]?(?:学习|复习|预习)?(?:计划|推荐|建议|安排)|" +
                "\\d{1,2}[.月/]\\d{1,2}[的]?(?:学习|复习|预习)?(?:计划|推荐|建议|安排)|" +
                "今日任务|推荐学习计划"
        ).matcher(text).find();
        if (todayPlan && found.size() >= 2) {
            events.addAll(found);
            log.info("p14今日计划匹配: date={}, {}条任务", planDate, found.size());
        }
        return events;
    }

    /**
     * 解析"时钟时间表格" — 第一列是时钟时间（09:00-09:30），第二列是任务标题，第三列类型（可选）：
     *   | 时间       | 任务内容                          | 类型       | 资源/目标 |
     *   | 09:00-09:30 | 理解哈希表核心概念（哈希函数、冲突） | 理论学习   | ... |
     * 表格本身无日期列：日期取标题里的"今天（2026-08-05，周三）"等，缺省为今天。
     * 时间与类型并入描述。返回空列表表示文本中没有此格式。
     */
    private List<StudyEvent> parseClockTable(Long userId, String text, LocalDate today) {
        List<StudyEvent> events = new ArrayList<>();
        if (text == null || text.isBlank()) return events;

        // 日期：优先取标题里的日期「今天（2026-08-05，周三）/（8.5）/（2026-08-05）」，缺省为今天
        // 注意：预处理已把"今天"替换为"M.d"（如 8.5），故直接匹配括号内的完整/简写日期
        LocalDate planDate = today;
        Matcher mFull = Pattern.compile(
                "[（(]\\s*(\\d{4})\\s*[-年/.月]\\s*(\\d{1,2})\\s*[-日/.月]\\s*(\\d{1,2})"
        ).matcher(text);
        if (mFull.find()) {
            planDate = LocalDate.of(Integer.parseInt(mFull.group(1)),
                    Integer.parseInt(mFull.group(2)), Integer.parseInt(mFull.group(3)));
        } else {
            // 简写日期（8.5）：兼容「（8.5，周三）」——日期后可能跟周几再闭合括号
            Matcher mShort = Pattern.compile(
                    "[（(]\\s*(\\d{1,2})\\s*[.月/]\\s*(\\d{1,2})\\s*[日]?\\s*" +
                    "(?:[，,]\\s*周[一二三四五六日天])?\\s*[）)]"
            ).matcher(text);
            if (mShort.find()) {
                planDate = resolveDate(Integer.parseInt(mShort.group(1)), Integer.parseInt(mShort.group(2)));
            }
        }

        // 时间表格行：第一列时钟时间（可带范围），第二列任务标题，第三列类型（可选）
        Pattern row = Pattern.compile(
                "\\|\\s*([0-9]{1,2}[:：][0-9]{2}(?:\\s*[-—~至到]\\s*[0-9]{1,2}[:：][0-9]{2})?)\\s*\\|\\s*" +
                "([^|\\n]{2,60}?)\\s*\\|" +
                "(?:\\s*([^|\\n]{0,20}?)\\s*\\|)?"
        );
        Matcher mr = row.matcher(text);
        List<StudyEvent> found = new ArrayList<>();
        while (mr.find()) {
            String title = mr.group(2).trim();
            title = title.replaceAll("[*`]", "").replaceAll("https?://\\S+", "").trim();
            if (title.length() < 2) continue;
            if (isNoiseTitle(title)) continue;
            String time = mr.group(1).trim();
            String type = mr.group(3) != null ? mr.group(3).trim() : "";
            if (type.equals("---") || type.isBlank()) type = "";
            String desc = type.isEmpty() ? time : time + " " + type;
            if (desc.length() > 120) desc = desc.substring(0, 117) + "...";
            found.add(buildEvent(userId, title, planDate, planDate, "task",
                    pickColor(title, TASK_COLORS), desc));
        }
        // ≥2 行才采纳，避免单行时间误判
        if (found.size() >= 2) {
            events.addAll(found);
            log.info("p15时钟时间表格匹配: date={}, {}条任务", planDate, found.size());
        }
        return events;
    }

    /** 从学习计划文本头部提取科目名，如「计算机网络每日学习计划」→「计算机网络」 */
    private String extractPlanSubject(String text) {
        // 「30天计算机网络每日学习计划」这类标题先剥离"N天"前缀，避免科目名被污染成"天计算机网络"
        text = text.replaceFirst("^\\s*\\d+\\s*天\\s*", "");
        // 匹配「XX每日学习计划」「XX学习计划（30天版）」等；科目名非贪婪，避免吞入「每日」等词
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "([\\u4e00-\\u9fa5a-zA-Z]{2,10}?)(?:每日|\\d+天|学习计划|备考计划|复习计划)"
        ).matcher(text);
        if (m.find()) {
            String subj = m.group(1).trim();
            // 过滤噪音词
            if (!subj.matches(".*(每日|计划|方案|安排|日历|日程).*")) {
                return subj;
            }
        }
        return null;
    }

    /** 判断标题是否为对话噪音（问候语、连接词、AI开场白、日期碎片等） */
    private boolean isNoiseTitle(String title) {
        if (title == null) return true;
        if (title.length() > 35) return true;
        // 纯数字 + 日/月/号（日期碎片，如"5日""1日""3月"）
        if (title.matches("^\\d{1,2}[日月号]$")) return true;
        // 阶段名称被前导连接词污染（"分为基础阶段"、"和强化阶段"等）
        if (title.matches(".*[阶段期轮]$")) {
            String[] badPrefixes = {"分为", "和", "与", "即", "包括", "包含", "涵盖"};
            for (String p : badPrefixes) {
                if (title.startsWith(p) && title.length() > p.length() + 1) return true;
            }
        }
        // 常见 AI 开场/过渡句式 + 通用词
        String[] noisePatterns = {
            "以下是为", "已为你", "为您制定", "为你安排", "以下是",
            "好的", "没问题", "当然可以", "可以的",
            "你好", "您好", "请问", "谢谢", "不客气",
            "这样", "那么", "首先", "其次", "另外", "此外", "同时", "提醒",
            "计划", "任务", "安排", "日程"
        };
        for (String p : noisePatterns) {
            if (title.equals(p)) return true;  // 精确匹配，不误杀"学习计划"等复合词
        }
        // Markdown 段落标签碎片（"- 学习内容：" "- 任务：" "- 学习目标：" 等），独立成事件属垃圾
        if (title.matches("^(学习内容|学习目标|学习重点|学习任务|学习方法|学习安排|具体任务|学习计划|学习建议|每日学习建议|推荐资源|时间安排|时间范围|时间|任务|重点|目标|内容)[:：]?$")) return true;
        // 散文连接/开头碎片（"时间范围为" "分为四个阶段" "以下是一个" 等）
        if (title.matches("^(时间范围为|时间范围是|范围是|分为|包括|包含|涵盖|总共|为期|如下|以下是一个|以下是|分别|每周|每天|本周|全天|主题)$")) return true;
        // 以序号开头的标题（一、二、1. 等），为markdown标题碎片
        if (title.matches("^[一二三四五六七八九十]、.+")) return true;
        if (title.matches("^\\d+[.、].+")) return true;
        return false;
    }

    /** 提取匹配位置之后的描述文本，遇到表格/分隔线即停止 */
    private String tailDesc(String text, int matchEnd) {
        if (matchEnd >= text.length()) return null;
        int end = Math.min(text.length(), matchEnd + 500);
        String tail = text.substring(matchEnd, end);
        // 截断于表格、分隔线、子标题、编号列表之前
        int cut = tail.length();
        for (String marker : new String[]{"\n|", "\n---", "\n#", "\n>", "\n- "}) {
            int pos = tail.indexOf(marker);
            if (pos > 0 && pos < cut) cut = pos;
        }
        // 额外：遇到「\n数字. 」编号项时截断
        java.util.regex.Matcher numCut = java.util.regex.Pattern.compile("\\n\\d+[.、）)]\\s").matcher(tail);
        if (numCut.find() && numCut.start() > 0 && numCut.start() < cut) {
            cut = numCut.start();
        }
        tail = tail.substring(0, cut);
        // 去掉 HTML 标签
        tail = tail.replaceAll("<[^>]+>", "");
        // 替换制表符为空格
        tail = tail.replace('\t', ' ');
        // 去掉 AI 开场白/结尾语
        tail = tail.replaceAll("(?m)^[已既][为经].*?[。\\n]", "");
        tail = tail.replaceAll("(?m)^[已既]将[您你]的.*?[。\\n]", "");
        tail = tail.replaceAll("(?m)^以下是.*?[：:\\n]", "");
        tail = tail.replaceAll("(?m)^如果[需想].*?[！\\n]", "");
        // 压缩多余空白
        tail = tail.replaceAll("[ \\t]{2,}", " ").trim();
        // 去掉首尾标点
        tail = tail.replaceAll("^[：:，,、。（(）)\\-— \\t]+", "").trim();
        tail = tail.replaceAll("[，,、。（(）)\\-— \\t]+$", "").trim();
        if (tail.isEmpty()) return null;
        if (tail.length() > 300) tail = tail.substring(0, 300);
        return tail;
    }

    /** 去重：同类型 + 同标题 + 同日期范围内，保留标题最长的；不同类型互不干扰 */
    private List<StudyEvent> dedupByDateRange(List<StudyEvent> events) {
        // 按 "title|type|dateRange" 精确去重，不同标题的事件不合并
        Map<String, StudyEvent> bestByRange = new LinkedHashMap<>();
        for (StudyEvent e : events) {
            String key = e.getTitle() + "|" + e.getEventType() + "|" + e.getEventDate() + "|"
                    + (e.getEndDate() != null ? e.getEndDate() : e.getEventDate());
            StudyEvent existing = bestByRange.get(key);
            if (existing == null || e.getTitle().length() > existing.getTitle().length()) {
                bestByRange.put(key, e);
            }
        }
        return new ArrayList<>(bestByRange.values());
    }

    /**
     * 根据月/日推算实际日期。假设年份是今年，如果日期已过则推断为明年。
     */
    private LocalDate resolveDate(int month, int day) {
        int year = LocalDate.now().getYear();
        LocalDate date = LocalDate.of(year, month, day);
        // 如果日期在 12 个月之前 → 推到明年；否则保留当年（即使已过，如1月的计划仍属于今年）
        if (date.isBefore(LocalDate.now().minusMonths(12))) {
            date = LocalDate.of(year + 1, month, day);
        }
        return date;
    }

    /**
     * 获取今日任务列表
     */
    public List<StudyEvent> getTodayTasks(Long userId) {
        return studyEventMapper.findTodayByUserId(userId, LocalDate.now());
    }

    /**
     * 查询用户最近完成的学习任务（completed=1，按日期倒序），用于注入 AI 上下文
     */
    public List<StudyEvent> findCompletedEvents(Long userId, int limit) {
        return studyEventMapper.findCompletedByUserId(userId, limit);
    }

    /**
     * 学习完成统计（累计/近7天/未完成），用于注入 AI 上下文做量化分析
     */
    public Map<String, Integer> completionStats(Long userId) {
        Map<String, Integer> stats = new HashMap<>();
        stats.put("totalCompleted", studyEventMapper.countCompleted(userId));
        stats.put("weekCompleted", studyEventMapper.countCompletedSince(userId, LocalDate.now().minusDays(7)));
        stats.put("pending", studyEventMapper.countPending(userId));
        return stats;
    }

    /**
     * 切换任务完成状态，并同步 AI 记忆：
     * 勾选完成 → 写入"已完成任务"记忆（知识掌握类别），AI 之后能记住；
     * 取消勾选 → 按标题精确删除对应记忆。
     */
    public void toggleComplete(Long userId, Long eventId, Boolean completed) {
        StudyEvent existing = studyEventMapper.findById(eventId);
        if (existing == null || !existing.getUserId().equals(userId)) {
            throw new RuntimeException("事件不存在或无权限");
        }
        if (existing.getEndDate() != null) {
            // 跨天任务：按天打卡，勾选只影响当天，不牵连其他日期（修复反馈47：划掉今日任务导致长期任务全被标记完成）
            LocalDate today = LocalDate.now();
            Set<String> dates = parseCompletedDates(existing.getCompletedDates());
            if (Boolean.TRUE.equals(completed)) {
                dates.add(today.toString());
            } else {
                dates.remove(today.toString());
            }
            studyEventMapper.updateCompletedDates(eventId, dates.isEmpty() ? null : String.join(",", dates));
            // 记忆联动带当天日期：不同日期打卡/取消互不影响
            if (Boolean.TRUE.equals(completed)) {
                memoryExtractService.recordCompletion(userId, existing.getTitle(), today);
            } else {
                memoryExtractService.removeCompletion(userId, existing.getTitle(), today);
            }
            return;
        }
        studyEventMapper.updateCompleted(eventId, completed);
        // 记忆联动带上任务日期：不同日期的同名任务勾选/取消互不影响
        if (Boolean.TRUE.equals(completed)) {
            memoryExtractService.recordCompletion(userId, existing.getTitle(), existing.getEventDate());
        } else {
            memoryExtractService.removeCompletion(userId, existing.getTitle(), existing.getEventDate());
        }
    }

    /** 解析打卡日期串为有序去重集合（TreeSet 保证按日期排序且去重） */
    private Set<String> parseCompletedDates(String raw) {
        Set<String> dates = new TreeSet<>();
        if (raw != null && !raw.isBlank()) {
            Collections.addAll(dates, raw.split(","));
        }
        return dates;
    }

    private StudyEvent buildEvent(Long userId, String title, LocalDate start, LocalDate end,
                                   String type, String color) {
        return buildEvent(userId, title, start, end, type, color, null);
    }

    private StudyEvent buildEvent(Long userId, String title, LocalDate start, LocalDate end,
                                   String type, String color, String desc) {
        StudyEvent event = new StudyEvent();
        event.setUserId(userId);
        event.setTitle(title);
        event.setEventDate(start);
        event.setEndDate(end);
        event.setEventType(type);
        event.setSource("ai");
        event.setColor(color);
        event.setDescription(desc != null ? desc : start + " 至 " + end);
        return event;
    }
}
