package com.studentagent.studentagent;

import com.studentagent.studentagent.entity.StudyEvent;
import com.studentagent.studentagent.mapper.StudyEventMapper;
import com.studentagent.studentagent.service.CalendarService;
import com.studentagent.studentagent.service.MemoryExtractService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

/**
 * 日历导入-正则提取逻辑单元测试（纯解析，不加载 Spring 上下文）。
 * 覆盖：今日计划（三种格式）、3个月计划（阶段+示例表格+代码块）、代码块垃圾过滤。
 */
class CalendarImportExtractionTest {

    private CalendarService service;

    @BeforeEach
    void setUp() {
        // CALLS_REAL_METHODS：绕开 Spring 依赖，仅验证纯解析逻辑
        service = Mockito.mock(CalendarService.class, Mockito.CALLS_REAL_METHODS);
    }

    @SuppressWarnings("unchecked")
    private List<StudyEvent> parse(String text) throws Exception {
        Method m = CalendarService.class.getDeclaredMethod("parsePlanPhases", Long.class, String.class);
        m.setAccessible(true);
        return (List<StudyEvent>) m.invoke(service, 1L, text);
    }

    @Test
    @DisplayName("问题8：周表格 | 第X周 | 内容 | 任务 | 应提取12条周任务（3阶段+12周=15条）")
    void weekTableImported() throws Exception {
        String weekly = """
                为了帮助你制定一个高效的3个月计算机网络学习计划，以下是分阶段的建议安排：

                ### **第一阶段：基础阶段（第1-4周）**
                - **目标**：掌握计算机网络的基本概念和核心协议。
                - **学习内容**：
                  - OSI七层模型和TCP/IP四层模型

                ### **第二阶段：进阶阶段（第5-8周）**
                - **目标**：深入理解协议细节和网络设备工作原理。

                ### **第三阶段：实战与复习（第9-12周）**
                - **目标**：通过实战巩固知识，准备考试或面试。

                ### **具体每周安排示例**
                | 周数   | 学习内容                     | 任务                                                                 |
                |--------|------------------------------|----------------------------------------------------------------------|
                | 第1周  | OSI模型、TCP/IP模型          | 阅读教材，完成OSI模型对比练习                                       |
                | 第2周  | 物理层和数据链路层           | 使用Wireshark抓包分析                                               |
                | 第3周  | 网络层（IP、ARP、ICMP）      | 配置静态路由实验                                                    |
                | 第4周  | 传输层（TCP、UDP）           | 分析TCP三次握手和四次挥手                                           |
                | 第5周  | 路由协议（RIP、OSPF）        | 使用Packet Tracer模拟路由协议                                       |
                | 第6周  | VLAN、VPN、NAT               | 搭建包含VLAN的小型网络                                              |
                | 第7周  | 网络安全基础                 | 学习加密技术，配置防火墙规则                                        |
                | 第8周  | 无线网络和移动网络           | 分析Wi-Fi和4G/5G网络的区别                                          |
                | 第9周  | 综合复习                     | 整理笔记，完成模拟测试                                              |
                | 第10周 | 网络排错与优化               | 解决常见的网络问题（如延迟、丢包）                                  |
                | 第11周 | 面试题准备                   | 刷题（如TCP/IP相关面试题）                                          |
                | 第12周 | 实战演练                     | 完成一个完整的网络项目（如小型企业网络设计）                        |
                """;
        List<StudyEvent> events = parse(weekly);
        assertEquals(15, events.size(), "应提取 3 个阶段 plan + 12 条周任务");
        assertTrue(events.stream().anyMatch(e -> "第一阶段：基础阶段".equals(e.getTitle())
                        && "plan".equals(e.getEventType())),
                "阶段标题应作为 plan 事件");
        assertTrue(events.stream().anyMatch(e -> "第1周：OSI模型、TCP/IP模型".equals(e.getTitle())
                        && LocalDate.now().equals(e.getEventDate())
                        && LocalDate.now().plusDays(6).equals(e.getEndDate())),
                "第1周应为今天~今天+6天");
        assertTrue(events.stream().anyMatch(e -> "第12周：实战演练".equals(e.getTitle())
                        && LocalDate.now().plusDays(77).equals(e.getEventDate())
                        && LocalDate.now().plusDays(83).equals(e.getEndDate())),
                "第12周应为今天+77~今天+83天");
        assertFalse(events.stream().anyMatch(e -> e.getTitle() != null && e.getTitle().equals("学习内容")),
                "学习内容标签不应成为独立事件");
    }

    @Test
    @DisplayName("问题10：每周大标题应导入，且不再产生'学习计划''学习内容：''时间范围为'垃圾事件")
    void weeklyHeadingsImportedNoJunk() throws Exception {
        String ds = """
                以下是一个针对 **数据结构** 的基础学习计划，时间范围为 **8月1日至8月31日**，分为四个阶段，每周重点学习不同的数据结构和相关算法。

                ---

                ### **学习目标**
                1. 掌握常见数据结构的基本概念、特点及实现方式。
                2. 能够使用伪代码或编程语言实现基本操作。
                3. 了解数据结构在实际问题中的应用场景。

                ---

                ### **学习计划**

                #### **第一周：线性结构（8月1日-8月7日）**
                - **学习内容**：
                  - 数组（静态数组、动态数组）
                  - 链表（单链表、双链表、循环链表）
                  - 栈（LIFO）和队列（FIFO）
                - **任务**：
                  - 实现数组的基本操作（增删改查）。
                  - 实现链表的插入、删除和反转。

                #### **第二周：树形结构（8月8日-8月14日）**
                - **学习内容**：
                  - 二叉树（性质、遍历方式：前序、中序、后序）
                  - 二叉搜索树（BST）
                  - 堆（最大堆、最小堆）
                - **任务**：
                  - 实现二叉树的遍历（递归和非递归）。

                #### **第三周：高级树形结构和图（8月15日-8月21日）**
                - **学习内容**：
                  - 平衡二叉树（AVL树、红黑树）
                  - 图的基本概念（邻接矩阵、邻接表）
                - **任务**：
                  - 实现AVL树的旋转操作。

                #### **第四周：哈希和综合练习（8月22日-8月31日）**
                - **学习内容**：
                  - 哈希表（冲突解决方法：开放寻址、链地址法）
                  - 字符串匹配算法（KMP、Trie树）
                - **任务**：
                  - 实现哈希表的插入、删除和查找。

                ---

                ### **每日学习建议**
                1. **理论学习**（1小时）：
                   - 阅读教材（如《数据结构与算法分析》）。
                2. **代码实现**（1小时）：
                   - 根据当天学习内容，实现相关数据结构的代码。
                3. **题目练习**（1小时）：
                   - 完成1-2道LeetCode简单/中等题目。

                ---

                ### **推荐资源**
                - **书籍**：
                  - 《数据结构与算法分析》（Mark Allen Weiss）
                  - 《算法导论》（Thomas H. Cormen）
                - **在线课程**：
                  - B站：浙江大学《数据结构》（陈越）
                - **刷题平台**：
                  - LeetCode
                  - 牛客网

                如果需要更详细的每日任务安排或具体题目推荐，可以告诉我！
                """;
        List<StudyEvent> events = parse(ds);
        assertEquals(4, events.size(), "应提取 4 条每周大标题事件");
        assertTrue(events.stream().anyMatch(e -> "第一周：线性结构".equals(e.getTitle())
                        && LocalDate.of(2026, 8, 1).equals(e.getEventDate())
                        && LocalDate.of(2026, 8, 7).equals(e.getEndDate())),
                "第一周标题应导入且日期为8.1-8.7");
        assertTrue(events.stream().anyMatch(e -> "第四周：哈希和综合练习".equals(e.getTitle())
                        && LocalDate.of(2026, 8, 22).equals(e.getEventDate())
                        && LocalDate.of(2026, 8, 31).equals(e.getEndDate())),
                "第四周标题应导入且日期为8.22-8.31");
        assertFalse(events.stream().anyMatch(e -> e.getTitle() != null
                        && (e.getTitle().equals("学习计划") || e.getTitle().equals("学习内容：")
                        || e.getTitle().equals("时间范围为") || e.getTitle().equals("学习内容"))),
                "不应出现'学习计划''学习内容：''时间范围为'垃圾事件");
        StudyEvent week1 = events.stream().filter(e -> "第一周：线性结构".equals(e.getTitle())).findFirst().orElse(null);
        assertNotNull(week1);
        assertTrue(week1.getDescription() != null && week1.getDescription().contains("数组（静态数组、动态数组）"),
                "第一周的子要点应并入描述");
        assertTrue(week1.getDescription() != null && week1.getDescription().contains("实现链表的插入、删除和反转"),
                "任务子要点应并入描述");
        assertFalse(week1.getDescription() != null && week1.getDescription().contains("学习内容"),
                "描述中不应残留'学习内容'标签");
    }

    @Test
    @DisplayName("今日计划v1：标题带日期+行内时间段任务，提取5条且日期为2026-08-06")
    void todayPlanV1() throws Exception {
        String text = """
            李瑶，根据你的学习情况（数据结构与算法是薄弱科目），今天可以这样安排：

            **推荐学习计划（2026-08-06）**
            1. **数据结构与算法基础巩固**
               - 9:00-10:30：复习链表核心操作（伪代码+手写实现）
            2. **英语能力提升**
               - 11:00-11:30：高频词汇记忆（附20词表）
            3. **午休与运动**
               - 12:30-13:30：建议进行20分钟拉伸（久坐后必备）
            4. **算法实战训练**
               - 14:00-15:00：二叉树专项
            5. **自由拓展时间**
               - 16:00-17:00：复习之前的错题
            """;
        List<StudyEvent> events = parse(text);
        assertEquals(5, events.size(), "应提取 5 条时间段任务");
        for (StudyEvent e : events) {
            assertEquals(LocalDate.of(2026, 8, 6), e.getEventDate(), "日期应继承标题中的 2026-08-06");
            assertNotNull(e.getTitle());
            assertFalse(e.getTitle().contains("task"), "不应出现垃圾标题");
        }
    }

    @Test
    @DisplayName("今日计划v2：时间建议标签格式，提取6条")
    void todayPlanV2() throws Exception {
        String text = """
            李瑶，根据你的学习档案，以下是一个今日学习建议，供你参考：

            ### 今日学习推荐（2026-08-06）
            1. **数据结构与算法分析（薄弱科目）**
               - **时间建议**：9:00 - 10:30
               - **内容**：复习链表和二叉树的基础知识
               - **目标**：完成3-5道相关题目
            2. **英语学习（词汇积累）**
               - **时间建议**：11:00 - 12:00
               - **内容**：背诵20个高频单词
            3. **午休**
               - **时间建议**：12:00 - 13:30
            4. **数据结构与算法分析（进阶练习）**
               - **时间建议**：14:00 - 15:30
               - **内容**：学习图的表示方法
            5. **自由学习时间**
               - **时间建议**：16:00 - 17:00
            6. **总结与反思**
               - **时间建议**：19:00 - 19:30
            """;
        List<StudyEvent> events = parse(text);
        assertEquals(6, events.size(), "应提取 6 条编号任务");
        assertTrue(events.stream().allMatch(e -> LocalDate.of(2026, 8, 6).equals(e.getEventDate())));
    }

    @Test
    @DisplayName("今日计划v3：无日期但标题含'今日'，提取3条且日期默认为今天")
    void todayPlanV3() throws Exception {
        String text = """
            李，根据你的学习档案和当前情况，以下是今天的学习建议：

            ### 1. **数据结构与算法分析（薄弱科目）**
               - **目标**：巩固基础，解决薄弱点。
               - **具体任务**：复习链表和二叉树的基本概念，完成3-5道相关题目。
            ### 2. **英语学习**
               - **目标**：提升词汇量和阅读理解能力。
               - **具体任务**：背诵20个新单词，阅读一篇英文短文。
            ### 3. **自由学习时间**
               - **具体任务**：复习其他感兴趣的科目或完成未完成的任务。
            """;
        List<StudyEvent> events = parse(text);
        assertEquals(3, events.size(), "应提取 3 条编号任务");
        assertTrue(events.stream().allMatch(e -> LocalDate.now().equals(e.getEventDate())),
                "无明确日期时应默认今天");
    }

    @Test
    @DisplayName("3个月计划：示例表格8条且无 addEvent 代码块产生的垃圾事件")
    void threeMonthPlanNoJunk() throws Exception {
        String text = """
            ### 📅 **3个月计算机网络学习计划**
            **目标**：系统掌握核心协议（TCP/IP/HTTP/DNS等）+完成10个关键实验

            ### **阶段划分**
            | 阶段 | 周次 | 学习主题 | 关键任务 |
            | 基础 | 1-4周 | 网络分层/IP协议/子网划分 | 用Wireshark分析流量 |
            | 核心 | 5-8周 | TCP/UDP/HTTP/HTTPS | 实现TCP聊天程序 |

            ### 📆 **日历事件添加中...**
            | 日期 | 标题 | 类型 |
            | 2026-08-07 | 学习：OSI七层模型 | task |
            | 2026-08-07 | 实验：Wireshark抓取HTTP流量 | task |
            | 2026-08-14 | 学习：TCP三次握手 | task |
            | 2026-08-14 | 实验：Telnet模拟握手 | task |
            | 2026-09-20 | 学习：HTTPS加密原理 | task |
            | 2026-09-20 | 实验：OpenSSL配置证书 | task |
            | 2026-10-25 | 学习：IPv6地址格式 | task |
            | 2026-10-25 | 实验：搭建双栈网络环境 | task |

            ### 🛠️ 正在执行：
            ```python
            addEvent("学习：OSI七层模型", "2026-08-07", "2026-08-07", "task")
            addEvent("实验：Wireshark抓取HTTP流量", "2026-08-07", "2026-08-07", "task")
            # 持续添加剩余事件...
            ```
            """;
        List<StudyEvent> events = parse(text);
        assertFalse(events.stream().anyMatch(e -> e.getTitle() != null && e.getTitle().contains("task")),
                "addEvent 代码块不应产生标题为 task 的垃圾事件");
        assertTrue(events.stream().anyMatch(e -> e.getTitle() != null && e.getTitle().contains("OSI七层模型")),
                "示例表格中的有效事件应被提取");
        assertTrue(events.stream().anyMatch(e -> LocalDate.of(2026, 9, 20).equals(e.getEventDate())),
                "9月事件应被提取");
    }

    @Test
    @DisplayName("阶段规划整合：阶段作为标题、重点/里程碑并入描述，示例表格事件保留")
    void stagePlanIntegrated() throws Exception {
        String text = """
            ### 🌟 3个月计算机网络学习计划（每周5小时）

            ### 📅 **阶段规划**
            #### **阶段1：网络基础（第1-4周）**
            - **重点**：分层模型/子网划分/抓包分析
            - **里程碑**：用Wireshark分析日常网页访问的全过程
            #### **阶段2：协议深度（第5-8周）**
            - **重点**：TCP滑动窗口/HTTP消息头/HTTPS握手
            - **里程碑**：用Python实现简易HTTP服务器

            ### 📆 **日历事件添加中...**
            | 日期 | 标题 | 类型 | 时长 |
            | 2026-08-07 | 学习：OSI七层模型 vs TCP/IP | task | 1小时 |
            | 2026-08-07 | 实验：Wireshark抓取HTTP流量 | task | 0.5小时 |

            ```python
            addEvent("学习：OSI七层模型 vs TCP/IP", "2026-08-07", "2026-08-07", "task")
            ```
            """;
        List<StudyEvent> events = parse(text);
        // 阶段1、阶段2 各整合为一条 plan 事件
        assertTrue(events.stream().anyMatch(e -> e.getTitle() != null
                        && e.getTitle().contains("阶段1：网络基础")
                        && e.getDescription() != null && e.getDescription().contains("分层模型")),
                "阶段1应作为标题、重点应并入描述");
        assertTrue(events.stream().anyMatch(e -> e.getTitle() != null
                        && e.getTitle().contains("阶段2：协议深度")
                        && e.getDescription() != null && e.getDescription().contains("TCP滑动窗口")),
                "阶段2应作为标题、重点应并入描述");
        assertTrue(events.stream().anyMatch(e -> e.getTitle() != null && e.getTitle().contains("OSI七层模型 vs TCP/IP")),
                "示例表格事件应保留");
        assertFalse(events.stream().anyMatch(e -> e.getTitle() != null && e.getTitle().contains("task")),
                "不应出现垃圾事件");
    }

    @Test
    @DisplayName("时钟时间表格：| 09:00-09:30 | 任务 | 类型 |，日期取标题'今天（2026-08-05，周三）'")
    void clockTimeTable() throws Exception {
        String text = """
            今天（2026-08-05，周三）的学习安排：

            | 时间       | 任务内容                          | 类型       |
            |-----------|-----------------------------------|-----------|
            | 09:00-09:30 | 理解哈希表核心概念（哈希函数、冲突） | 理论学习   |
            | 09:30-10:00 | 哈希表插入删除操作练习             | 实操练习   |
            | 10:00-10:30 | 哈希冲突解决方案梳理               | 理论学习   |
            """;
        List<StudyEvent> events = parse(text);
        assertFalse(events.isEmpty(), "时钟时间表格应被提取");
        assertTrue(events.stream().allMatch(e -> LocalDate.of(2026, 8, 5).equals(e.getEventDate())),
                "日期应为 2026-08-05");
        assertTrue(events.stream().anyMatch(e -> e.getTitle() != null && e.getTitle().contains("哈希表")));
    }

    @Test
    @DisplayName("相对天数表格：| 1-2 | 数组 | 内容 |，≥3条采纳，日期从今天起算")
    void dayOffsetTable() throws Exception {
        String text = """
            30天刷题计划：

            | 天数  | 主题       | 内容                     |
            |-------|------------|--------------------------|
            | 1-2   | 数组       | 遍历/二分/双指针         |
            | 3-4   | 链表       | 反转/环形/合并           |
            | 5     | 栈与队列   | 单调栈/单调队列          |
            """;
        List<StudyEvent> events = parse(text);
        assertEquals(3, events.size(), "应提取 3 条表格任务");
        assertTrue(events.stream().anyMatch(e -> "数组".equals(e.getTitle())
                && LocalDate.now().equals(e.getEventDate())), "第1-2天=今天起");
        assertTrue(events.stream().anyMatch(e -> "链表".equals(e.getTitle())
                && LocalDate.now().plusDays(2).equals(e.getEventDate())), "第3-4天=今天+2天");
    }

    @Test
    @DisplayName("阶段标题带天数：基础构建阶段（第1-7天）→ plan 事件")
    void phaseWithDayRange() throws Exception {
        String text = """
            考研数学30天冲刺计划：
            **基础构建阶段（第1-7天）**
            **强化突破阶段（第8-21天）**
            **冲刺模拟阶段（第22-30天）**
            """;
        List<StudyEvent> events = parse(text);
        assertEquals(3, events.size(), "应提取 3 个阶段");
        assertTrue(events.stream().anyMatch(e -> e.getTitle().contains("基础构建阶段")
                && LocalDate.now().equals(e.getEventDate())), "第1天=今天");
        assertTrue(events.stream().anyMatch(e -> e.getTitle().contains("冲刺模拟阶段")
                && LocalDate.now().plusDays(21).equals(e.getEventDate())), "第22天=今天+21天");
    }

    @Test
    @DisplayName("HTML 标签清理：含 <br> 的今日计划仍能提取")
    void htmlTagsCleaned() throws Exception {
        String text = """
            今日学习计划（2026-08-06）：
            1. **高数复习**<br>
               - 9:00-10:30：极限计算专项<br>
            2. **英语**<br>
               - 11:00-12:00：词汇背诵<br>
            3. **午休**<br>
               - 12:30-13:30：午间休息<br>
            """;
        List<StudyEvent> events = parse(text);
        assertEquals(3, events.size(), "应提取 3 个时间段子任务");
        assertTrue(events.stream().allMatch(e -> LocalDate.of(2026, 8, 6).equals(e.getEventDate())));
    }

    @Test
    @DisplayName("普通聊天防误报：日常对话不应被提取为日历事件")
    void plainChatNotExtracted() throws Exception {
        String text = """
            今天去图书馆复习了链表，感觉还行。明天准备做两道题，后天再复习二叉树。
            你说 8.7 有个测验，我 8.6 晚上再突击一下。你觉得这样安排合理吗？
            """;
        List<StudyEvent> events = parse(text);
        assertTrue(events.isEmpty(), "普通对话不应被提取为日历事件");
    }

    @Test
    @DisplayName("空文本与纯 Markdown 标题：返回空，不抛异常")
    void blankAndHeadingOnly() throws Exception {
        assertTrue(parse("").isEmpty());
        assertTrue(parse("   \n\n  ").isEmpty());
        assertTrue(parse("# 学习计划\n## 第一章\n### 1.1 引言").isEmpty(),
                "只有标题没有具体任务时不应提取");
    }

    // ==================== Bug 修复回归测试 ====================

    @Test
    @DisplayName("AI兜底日期容错：M.d、yyyy/M/d、M月d日 都能解析，非法日期返回null")
    void flexibleDateParsing() throws Exception {
        Method m = CalendarService.class.getDeclaredMethod("parseFlexibleDate", String.class);
        m.setAccessible(true);
        assertEquals(LocalDate.of(2026, 8, 6), m.invoke(service, "2026-08-06"));
        assertEquals(LocalDate.of(2026, 8, 5), m.invoke(service, "2026/8/5"));
        assertEquals(LocalDate.of(2026, 8, 5), m.invoke(service, "2026年8月5日"));
        assertEquals(LocalDate.of(2026, 8, 5), m.invoke(service, "8.5"));
        assertEquals(LocalDate.of(2026, 8, 5), m.invoke(service, "8月5日"));
        assertNull(m.invoke(service, "2月30日"), "非法日期应返回null并跳过该条");
        assertNull(m.invoke(service, "abc"));
        assertNull(m.invoke(service, ""));
    }

    @Test
    @DisplayName("科目提取：「30天计算机网络每日学习计划」→「计算机网络」而非「天计算机网络」")
    void planSubjectExtraction() throws Exception {
        Method m = CalendarService.class.getDeclaredMethod("extractPlanSubject", String.class);
        m.setAccessible(true);
        assertEquals("计算机网络", m.invoke(service, "30天计算机网络每日学习计划"));
        assertEquals("计算机网络", m.invoke(service, "计算机网络学习计划"));
        assertNull(m.invoke(service, "随便聊聊今天天气"));
    }

    @Test
    @DisplayName("手动导入替换：只删同标题同日期范围旧事件，绝不按用户/标题整体删除")
    void importReplaceScope() throws Exception {
        StudyEventMapper mapper = Mockito.mock(StudyEventMapper.class);
        CalendarService real = new CalendarService(mapper);
        Mockito.when(mapper.deleteByTitleInRange(Mockito.anyLong(), Mockito.anyString(),
                Mockito.any(LocalDate.class), Mockito.any(LocalDate.class))).thenReturn(0);
        Mockito.when(mapper.insert(Mockito.any(StudyEvent.class))).thenReturn(1);

        String text = """
                数据结构复习计划：
                | 7.29-7.30 | 复习数据结构 | 链表 | 数组 |
                计算机网络计划：
                | 8.1-8.2 | 学习TCP三次握手 | 实验 |
                """;
        real.extractAndSave(1L, text);

        // 每个事件按「标题 + 其自身日期范围」精确删除，替换策略不得触碰其他事件
        verify(mapper, Mockito.atLeastOnce()).deleteByTitleInRange(Mockito.eq(1L), Mockito.eq("复习数据结构"),
                Mockito.any(LocalDate.class), Mockito.any(LocalDate.class));
        verify(mapper, Mockito.atLeastOnce()).deleteByTitleInRange(Mockito.eq(1L), Mockito.eq("学习TCP三次握手"),
                Mockito.any(LocalDate.class), Mockito.any(LocalDate.class));
        verify(mapper, Mockito.never()).deleteByUserId(Mockito.anyLong());
        verify(mapper, Mockito.never()).deleteByTitle(Mockito.anyLong(), Mockito.anyString());
    }

    @Test
    @DisplayName("勾选/取消完成联动记忆带日期：不同日期的同名任务互不影响")
    void toggleCompleteWithDate() throws Exception {
        StudyEventMapper mapper = Mockito.mock(StudyEventMapper.class);
        MemoryExtractService memSvc = Mockito.mock(MemoryExtractService.class);
        CalendarService real = new CalendarService(mapper);
        // memoryExtractService 是 @Lazy @Autowired 字段，用反射注入 mock
        java.lang.reflect.Field f = CalendarService.class.getDeclaredField("memoryExtractService");
        f.setAccessible(true);
        f.set(real, memSvc);

        StudyEvent ev = new StudyEvent();
        ev.setEventId(100L);
        ev.setUserId(1L);
        ev.setTitle("复习链表");
        ev.setEventDate(LocalDate.of(2026, 8, 1));
        Mockito.when(mapper.findById(100L)).thenReturn(ev);
        Mockito.when(mapper.updateCompleted(Mockito.eq(100L), Mockito.anyBoolean())).thenReturn(1);

        real.toggleComplete(1L, 100L, true);
        verify(memSvc).recordCompletion(1L, "复习链表", LocalDate.of(2026, 8, 1));

        real.toggleComplete(1L, 100L, false);
        verify(memSvc).removeCompletion(1L, "复习链表", LocalDate.of(2026, 8, 1));
    }
}
