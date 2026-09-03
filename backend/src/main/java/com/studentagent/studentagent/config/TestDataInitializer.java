package com.studentagent.studentagent.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 测试数据初始化器：启动时自动创建 3 个不同画像的测试用户及关联数据。
 * 仅在 dev 环境下激活。每次启动都会清除旧测试数据并重新插入（确保密码正确）。
 */
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class TestDataInitializer implements CommandLineRunner {

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public void run(String... args) {
        log.info("========== 开始初始化测试数据 ==========");

        try {
            // 先清除旧的测试数据（避免 INSERT IGNORE 跳过）
            cleanOldData();
            initKaoyanUser();
            initQimoUser();
            initFreshmanUser();
            log.info("========== 测试数据初始化完成 ==========");
            log.info("登录账号：kaoyan_zhang / qimo_lihua / freshman_wang  密码均为 123456");
        } catch (Exception e) {
            log.error("测试数据初始化失败", e);
        }
    }

    /**
     * 清除所有旧测试数据（按外键依赖顺序删除）
     */
    private void cleanOldData() {
        int c = 0;
        c += jdbc.update("DELETE FROM chat_message WHERE session_id IN (SELECT session_id FROM chat_session WHERE user_id IN (11,12,13))");
        c += jdbc.update("DELETE FROM chat_session WHERE user_id IN (11,12,13)");
        c += jdbc.update("DELETE FROM memory_record WHERE user_id IN (11,12,13)");
        c += jdbc.update("DELETE FROM study_event WHERE user_id IN (11,12,13)");
        c += jdbc.update("DELETE FROM weekly_report WHERE user_id IN (11,12,13)");
        c += jdbc.update("DELETE FROM student_profile WHERE user_id IN (11,12,13)");
        c += jdbc.update("DELETE FROM sys_user WHERE user_id IN (11,12,13)");
        if (c > 0) {
            log.info("已清除 {} 条旧测试数据", c);
        }
    }

    // ========================
    // 用户 A：考研冲刺型 - 张明
    // ========================
    private void initKaoyanUser() {
        String pwd = passwordEncoder.encode("123456");

        jdbc.update("INSERT INTO sys_user (user_id, username, password, name, major, grade, user_tags, create_time) " +
                "VALUES (?, 'kaoyan_zhang', ?, '张明', '计算机科学与技术', '大三', " +
                "'考研备考|5,数学薄弱|4,408复习中|3,政治刚起步|2,早起学习|4', '2026-07-01 08:00:00')", 11, pwd);

        jdbc.update("INSERT INTO student_profile (user_id, weak_subjects, exam_plans, study_goals, update_time) " +
                "VALUES (?, ?, ?, ?, ?)", 11,
                "高等数学,线性代数,政治",
                "2025年12月21日考研初试，数学一、英语一、政治、408计算机综合",
                "考研上岸985院校，数学一目标120+，408目标130+",
                "2026-07-25 22:00:00");

        // 会话
        jdbc.update("INSERT INTO chat_session (session_id, user_id, title, title_locked, create_time, last_active_time) VALUES " +
                "(101,11,'考研数学复习规划',1,'2026-07-22 09:00:00','2026-07-25 22:30:00')," +
                "(102,11,'408数据结构怎么复习',0,'2026-07-26 10:00:00','2026-07-28 15:00:00')," +
                "(103,11,'政治现在开始来得及吗',0,'2026-07-30 19:00:00','2026-07-30 19:30:00')");

        // 消息
        jdbc.update("INSERT INTO chat_message (message_id, session_id, role, content, create_time) VALUES " +
                "(1001,101,'user','帮我生成考研数学一的复习计划，基础阶段8.1-8.31，强化阶段9.1-10.15','2026-07-22 09:00:00')," +
                "(1002,101,'assistant','好的，以下是为您制定的考研数学一复习计划：基础阶段8.1-8.31复习函数极限连续、导数微分、积分学；强化阶段9.1-10.15李永乐复习全书+张宇1000题。','2026-07-22 09:00:30')," +
                "(1003,101,'user','高数里的多元微积分我特别薄弱，有什么好的学习方法吗','2026-07-22 09:05:00')," +
                "(1004,101,'assistant','多元微积分确实是数学一的重难点。建议先理解几何意义、分题型刷题，每天做3道。需要我帮你加到日历里吗？','2026-07-22 09:06:00')," +
                "(1005,101,'user','好的，帮我加到日历里','2026-07-22 09:07:00')," +
                "(1006,101,'assistant','已将多元微积分专项训练添加到日历中，每天19:00-21:00，从7月31日持续到8月31日。','2026-07-22 09:07:30')," +
                "(1007,101,'user','408的综合题好难啊，特别是数据结构的算法题','2026-07-24 20:30:00')," +
                "(1008,101,'assistant','408数据结构算法题关键是掌握常见模板：链表反转、二叉树遍历、二分查找、快排分区函数。建议每天手写2道模拟考场环境。','2026-07-24 20:31:00')," +
                "(1009,102,'user','408数据结构算法大题怎么破？每次都写不出来','2026-07-26 10:00:00')," +
                "(1010,102,'assistant','先写思路再写代码，时间复杂度分析必写。真题刷了多少套？','2026-07-26 10:01:00')," +
                "(1011,102,'user','真题才刷了5套，感觉时间不够用了','2026-07-26 10:03:00')," +
                "(1012,102,'assistant','5套在7月底算正常进度。8月上午数学下午408晚上政治，时间够的。','2026-07-26 10:04:00')," +
                "(1013,103,'user','现在7月底了，政治还没开始看，来得及吗？好慌','2026-07-30 19:00:00')," +
                "(1014,103,'assistant','完全来得及！每天1小时看马原，9月刷1000题，12月背肖四。政治是投入产出比最高的科目。','2026-07-30 19:02:00')," +
                "(1015,103,'user','那英语呢？我英语基础一般，四级飘过','2026-07-30 19:05:00')," +
                "(1016,103,'assistant','每天100个单词+1篇真题阅读，11月开始背作文模板。英语一目标50-55分就够了。','2026-07-30 19:06:00')");

        // 记忆
        jdbc.update("INSERT INTO memory_record (user_id, memory_text, vector_id, create_time) VALUES " +
                "(11,'薄弱科目-高等数学-多元微积分计算能力不足','vec_11_001','2026-07-22 09:10:00')," +
                "(11,'薄弱科目-高等数学-级数收敛判断不熟练','vec_11_002','2026-07-22 09:11:00')," +
                "(11,'薄弱科目-408-数据结构算法题写不出完整代码','vec_11_003','2026-07-24 20:35:00')," +
                "(11,'薄弱科目-政治-还没开始复习感到焦虑','vec_11_004','2026-07-30 19:10:00')," +
                "(11,'学习目标-考研-目标985院校计算机专业','vec_11_005','2026-07-22 09:12:00')," +
                "(11,'考试计划-考研-2025年12月21日考研初试','vec_11_006','2026-07-25 22:00:00')," +
                "(11,'学习习惯-作息-每天6点起床背单词','vec_11_007','2026-07-23 08:00:00')," +
                "(11,'学习习惯-刷题-每天一套数学真题','vec_11_008','2026-07-24 21:00:00')," +
                "(11,'知识掌握-英语-阅读正确率60%左右','vec_11_009','2026-07-25 20:00:00')," +
                "(11,'薄弱科目-英语-写作模板还没开始准备','vec_11_010','2026-07-30 19:15:00')," +
                "(11,'知识掌握-高等数学-定积分计算熟练','vec_11_011','2026-07-26 16:00:00')");

        // 日历
        jdbc.update("INSERT INTO study_event (user_id, title, event_date, end_date, description, event_type, source, color, completed, create_time) VALUES " +
                "(11,'多元微积分专项训练','2026-07-31','2026-08-31','每天19:00-21:00','task','ai','#E6A23C',0,'2026-07-22 09:08:00')," +
                "(11,'数学基础阶段','2026-08-01','2026-08-31','函数极限连续、导数微分、积分学','plan','ai','#409EFF',0,'2026-07-22 09:09:00')," +
                "(11,'数学强化阶段','2026-09-01','2026-10-15','李永乐复习全书+张宇1000题','plan','ai','#67C23A',0,'2026-07-22 09:09:00')," +
                "(11,'背考研单词','2026-07-31','2026-12-20','每天100个单词','task','manual','#9B59B6',0,'2026-07-23 08:00:00')," +
                "(11,'考研初试','2025-12-21','2025-12-22','数学一、英语一、政治、408','exam','manual','#F56C6C',0,'2026-07-25 22:00:00')," +
                "(11,'政治马原入门','2026-08-01','2026-08-31','每天1小时徐涛强化课','task','ai','#1ABC9C',0,'2026-07-30 19:10:00')");

        // 周报
        jdbc.update("INSERT INTO weekly_report (user_id, week_start, week_end, content, create_time) VALUES " +
                "(11,'2026-07-28','2026-08-03','📊 本周学习周报（7.28-8.03）\n📝 学习概况：对话8次，新增记忆5条\n🎯 薄弱项：多元微积分提及3次，政治刚开始\n💡 下周建议：完成多元积分第一阶段，开始线代强化\n📈 趋势：数学强度较上周+40%','2026-07-31 10:00:00')," +
                "(11,'2026-07-21','2026-07-27','📊 本周学习周报（7.21-7.27）\n📝 学习概况：对话5次，新增记忆3条\n🎯 薄弱项：多元微积分刚开始攻克\n💡 下周建议：保持数学节奏，开始政治复习','2026-07-27 22:00:00')");

        log.info("已创建考研用户 张明 (kaoyan_zhang)");
    }

    // ========================
    // 用户 B：期末冲刺型 - 李华
    // ========================
    private void initQimoUser() {
        String pwd = passwordEncoder.encode("123456");

        jdbc.update("INSERT INTO sys_user (user_id, username, password, name, major, grade, user_tags, create_time) " +
                "VALUES (?, 'qimo_lihua', ?, '李华', '软件工程', '大二', " +
                "'期末冲刺|5,数据结构薄弱|4,计网需强化|3,刷题党|4,熬夜学习|2', '2026-07-15 10:30:00')", 12, pwd);

        jdbc.update("INSERT INTO student_profile (user_id, weak_subjects, exam_plans, study_goals, update_time) VALUES " +
                "(?, ?, ?, ?, ?)", 12,
                "数据结构,计算机网络",
                "2026年1月15日期末考试，2026年1月20日数据结构考试",
                "期末不挂科，绩点提到3.5，数据结构考85分以上",
                "2026-07-28 18:30:00");

        jdbc.update("INSERT INTO chat_session (session_id, user_id, title, title_locked, create_time, last_active_time) VALUES " +
                "(104,12,'数据结构期末怎么复习',0,'2026-07-23 14:00:00','2026-07-29 21:00:00')," +
                "(105,12,'考前刷题计划',0,'2026-07-27 16:00:00','2026-07-30 11:00:00')");

        jdbc.update("INSERT INTO chat_message (message_id, session_id, role, content, create_time) VALUES " +
                "(2001,104,'user','数据结构期末考试复习计划，二叉树和图论的部分不太会','2026-07-23 14:00:00')," +
                "(2002,104,'assistant','期末复习抓重点！二叉树占40%图论占30%。4种遍历、BST、AVL旋转、哈夫曼树是必考题。','2026-07-23 14:01:00')," +
                "(2003,104,'user','帮我生成一个吧，距离考试还有两周，每天能学3小时','2026-07-23 14:02:00')," +
                "(2004,104,'assistant','14天冲刺计划：前2天线性表，2天栈队列，3天二叉树专题，2天图论，2天查找排序，最后综合模拟。每天3h=1h知识点+1.5h手写代码+0.5h总结。','2026-07-23 14:03:00')," +
                "(2005,104,'user','二叉树的非递归遍历好难，写几遍都记不住','2026-07-24 20:00:00')," +
                "(2006,104,'assistant','非递归遍历靠口诀！前序：根入栈弹出访右先左后。中序：一路向左入栈。后序用双栈法——后序=前序(根右左)的反转！','2026-07-24 20:01:00')," +
                "(2007,104,'user','后序遍历，感觉思路很绕','2026-07-24 20:02:00')," +
                "(2008,104,'assistant','双栈法：根入栈1→弹出入栈2→左右孩子入栈1→重复→弹出栈2即后序。本质是前序反转！','2026-07-24 20:03:00')," +
                "(2009,105,'user','我的计算机网络也很薄弱，TCP三次握手四次挥手老是搞混','2026-07-27 16:00:00')," +
                "(2010,105,'assistant','三次握手=打电话（喂-听到了-开始聊），四次挥手=挂电话（挂了-收到-我也挂了-OK）。挥手多一次因为TCP全双工。','2026-07-27 16:01:00')," +
                "(2011,105,'user','那OSI七层和TCP/IP四层的对应关系能梳理一下吗','2026-07-28 10:00:00')," +
                "(2012,105,'assistant','应表会传网数物，口诀从上往下记。OSI上三层对应TCP/IP应用层，考试一般考5层模型。','2026-07-28 10:01:00')," +
                "(2013,105,'user','明天就要考计网了，最后有什么注意事项吗','2026-07-29 21:00:00')," +
                "(2014,105,'assistant','最后一天不看新题只看错题！重点回顾TCP拥塞控制、IP子网划分、HTTP状态码。加油！','2026-07-29 21:01:00')");

        jdbc.update("INSERT INTO memory_record (user_id, memory_text, vector_id, create_time) VALUES " +
                "(12,'薄弱科目-数据结构-二叉树非递归遍历记不住','vec_12_001','2026-07-24 20:10:00')," +
                "(12,'薄弱科目-数据结构-图论算法理解不透彻','vec_12_002','2026-07-23 14:10:00')," +
                "(12,'薄弱科目-计算机网络-TCP三次握手四次挥手混淆','vec_12_003','2026-07-27 16:10:00')," +
                "(12,'薄弱科目-计算机网络-OSI七层模型记不牢','vec_12_004','2026-07-28 10:10:00')," +
                "(12,'考试计划-期末-2026年1月15日期末考试','vec_12_005','2026-07-23 14:12:00')," +
                "(12,'考试计划-期末-2026年1月20日数据结构考试','vec_12_006','2026-07-28 18:00:00')," +
                "(12,'学习目标-绩点-期末绩点目标3.5','vec_12_007','2026-07-23 14:13:00')," +
                "(12,'学习习惯-刷题-每天刷LeetCode简单题2道','vec_12_008','2026-07-24 21:30:00')," +
                "(12,'薄弱科目-数据结构-后序遍历思路绕容易出错','vec_12_009','2026-07-24 20:30:00')," +
                "(12,'知识掌握-数据结构-排序算法掌握较好','vec_12_010','2026-07-25 16:00:00')");

        jdbc.update("INSERT INTO study_event (user_id, title, event_date, end_date, description, event_type, source, color, completed, create_time) VALUES " +
                "(12,'二叉树专题复习','2026-07-31','2026-08-01','遍历+BST+哈夫曼树手写代码','task','ai','#E6A23C',0,'2026-07-23 14:05:00')," +
                "(12,'图论专题复习','2026-08-02','2026-08-03','DFS/BFS/最短路/拓扑排序','task','ai','#9B59B6',0,'2026-07-23 14:05:00')," +
                "(12,'排序算法复习','2026-08-04','2026-08-05','快排/堆排/归并','task','ai','#3498DB',0,'2026-07-23 14:06:00')," +
                "(12,'期末考试','2026-01-15','2026-01-20','专业课期末考试周','exam','manual','#F56C6C',0,'2026-07-23 14:15:00')," +
                "(12,'计网TCP专题','2026-07-31','2026-08-01','三次握手四次挥手、拥塞控制','task','manual','#1ABC9C',0,'2026-07-28 10:15:00')," +
                "(12,'刷LeetCode','2026-07-31','2026-08-06','每天2道简单题','task','ai','#67C23A',0,'2026-07-24 21:00:00')");

        jdbc.update("INSERT INTO weekly_report (user_id, week_start, week_end, content, create_time) VALUES " +
                "(12,'2026-07-28','2026-08-03','📊 本周学习周报（7.28-8.03）\n📝 学习概况：对话6次，新增记忆4条\n🎯 薄弱项：二叉树后序遍历需巩固，图论还需多练\n💡 下周建议：完成排序专题，做3套模拟卷\n📈 趋势：数据结构掌握程度明显提升','2026-07-31 12:00:00')");

        log.info("已创建期末用户 李华 (qimo_lihua)");
    }

    // ========================
    // 用户 C：大一探索型 - 王芳
    // ========================
    private void initFreshmanUser() {
        String pwd = passwordEncoder.encode("123456");

        jdbc.update("INSERT INTO sys_user (user_id, username, password, name, major, grade, user_tags, create_time) " +
                "VALUES (?, 'freshman_wang', ?, '王芳', '计算机科学与技术', '大一', " +
                "'C语言入门|4,探索阶段|3,规律学习|3', '2026-07-20 14:00:00')", 13, pwd);

        jdbc.update("INSERT INTO student_profile (user_id, weak_subjects, exam_plans, study_goals, update_time) VALUES " +
                "(?, NULL, NULL, ?, ?)", 13,
                "探索计算机专业方向，打好C语言和数学基础，找到感兴趣的研究方向",
                "2026-07-30 09:00:00");

        jdbc.update("INSERT INTO chat_session (session_id, user_id, title, title_locked, create_time, last_active_time) VALUES " +
                "(106,13,'大一C语言怎么学',0,'2026-07-25 10:00:00','2026-07-30 16:00:00')," +
                "(107,13,'计算机专业方向选择',0,'2026-07-28 14:00:00','2026-07-29 18:00:00')");

        jdbc.update("INSERT INTO chat_message (message_id, session_id, role, content, create_time) VALUES " +
                "(3001,106,'user','刚上大学，C语言感觉好难，指针完全搞不懂','2026-07-25 10:00:00')," +
                "(3002,106,'assistant','指针=门牌号！变量是房间里的东西，指针是房间的门牌号。*p是通过门牌号找到东西，&x是获取门牌号。','2026-07-25 10:01:00')," +
                "(3003,106,'user','指针的指针 **p 这种，感觉像是套娃','2026-07-25 10:03:00')," +
                "(3004,106,'assistant','就是套娃！一个星号跳一次到指向的内容，两个星号跳两次。大一先搞透一级指针就行。','2026-07-25 10:04:00')," +
                "(3005,106,'user','我们专业以后有哪些方向可以选？感觉计算机好广','2026-07-26 15:00:00')," +
                "(3006,106,'assistant','大一不急着定方向。后端/前端/AI/安全/嵌入式各有特点。大一打好C语言基础，多做小项目多尝试！','2026-07-26 15:02:00')," +
                "(3007,107,'user','我想了解一下数据结构这门课，下学期要学，难吗？','2026-07-28 14:00:00')," +
                "(3008,107,'assistant','数据结构是计算机最重要的课之一。线性结构简单，树中等，图较难。暑假可以看王道考研课预习。','2026-07-28 14:01:00')," +
                "(3009,107,'user','我数学还行，英语一般，适合走AI方向吗','2026-07-29 18:00:00')," +
                "(3010,107,'assistant','数学好是很大优势！AI需要线代概率论微积分。英语可以边学边补，暑假看吴恩达机器学习课试试。','2026-07-29 18:01:00')");

        jdbc.update("INSERT INTO memory_record (user_id, memory_text, vector_id, create_time) VALUES " +
                "(13,'薄弱科目-C语言-指针概念理解不透彻','vec_13_001','2026-07-25 10:10:00')," +
                "(13,'薄弱科目-C语言-二级指针像套娃','vec_13_002','2026-07-25 10:12:00')," +
                "(13,'学习目标-方向探索-想了解计算机各专业方向','vec_13_003','2026-07-26 15:10:00')," +
                "(13,'学习习惯-规律-每天固定学习2小时编程','vec_13_004','2026-07-26 16:00:00')," +
                "(13,'知识掌握-数学-数学基础较好','vec_13_005','2026-07-29 18:10:00')," +
                "(13,'薄弱科目-英语-英语基础一般靠四级水平','vec_13_006','2026-07-29 18:12:00')," +
                "(13,'学习目标-AI方向-对人工智能感兴趣想深入了解','vec_13_007','2026-07-29 18:15:00')," +
                "(13,'考试计划-暂无-暂无考试压力处于探索期','vec_13_008','2026-07-28 14:15:00')");

        jdbc.update("INSERT INTO study_event (user_id, title, event_date, end_date, description, event_type, source, color, completed, create_time) VALUES " +
                "(13,'C语言指针练习','2026-07-31','2026-08-07','每天练习指针习题5道','task','ai','#E6A23C',0,'2026-07-25 10:15:00')," +
                "(13,'看数据结构预习视频','2026-08-01','2026-08-15','B站王道考研数据结构课','task','manual','#409EFF',0,'2026-07-28 14:15:00')," +
                "(13,'Python入门学习','2026-08-08','2026-08-21','Coursera Python课程','task','manual','#67C23A',0,'2026-07-29 18:20:00')");

        jdbc.update("INSERT INTO weekly_report (user_id, week_start, week_end, content, create_time) VALUES " +
                "(13,'2026-07-28','2026-08-03','📊 本周学习周报（7.28-8.03）\n📝 学习概况：对话4次，新增记忆3条\n🎯 成长观察：指针从不懂到能练习，进步显著！方向探索中\n💡 下周建议：C语言做通讯录小项目，看AI入门视频\n📈 趋势：学习节奏稳定，对专业方向逐渐清晰','2026-07-31 16:00:00')");

        log.info("已创建新生用户 王芳 (freshman_wang)");
    }
}
