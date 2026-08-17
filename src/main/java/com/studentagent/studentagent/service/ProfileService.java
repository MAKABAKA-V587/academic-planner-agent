package com.studentagent.studentagent.service;

import com.studentagent.studentagent.dto.ProfileDTO;
import com.studentagent.studentagent.dto.ProfileVO;
import com.studentagent.studentagent.dto.TagVO;
import com.studentagent.studentagent.entity.MemoryRecord;
import com.studentagent.studentagent.entity.StudentProfile;
import com.studentagent.studentagent.mapper.MemoryRecordMapper;
import com.studentagent.studentagent.mapper.ProfileMapper;
import com.studentagent.studentagent.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 学业档案服务，含档案修改后同步向量库（阶段3.4）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final ProfileMapper profileMapper;
    private final VectorStore vectorStore;
    private final MemoryRecordMapper memoryRecordMapper;
    private final UserMapper userMapper;
    private final ChatClient chatClient;
    private final StringRedisTemplate redisTemplate;

    private static final String TAG_LOCK_PREFIX = "tag:generate:";
    private static final int TAG_INTERVAL_SECONDS = 600; // 10分钟内不重复生成标签

    private static final String TAG_GENERATE_PROMPT = """
            你是一个学习画像分析器。根据用户的学习记忆，提炼3~5个具体的学业画像标签并标注权重。
            标签要求：
            - 简短精炼（2~6字），必须是具体的个人特征，不能是泛泛的类别名
            - 【严禁】使用以下泛化词作为标签：学习习惯、薄弱科目、考试计划、学习目标、知识掌握、学习方法、学习状态、复盘总结、错题整理、进度监控
            - 每个标签应唯一，反映用户的具体情况（具体科目、具体目标、具体习惯）
            - 权重代表该特征在用户学习中的突出程度，范围1-5（5=最突出）
            输出格式：「标签名|权重」，一行一个，不要序号，不要额外解释。
            好的标签示例：
            考研备考|5
            数学薄弱|3
            线性代数需强化|4
            番茄钟学习|2
            四级冲刺|4
            坏的标签（严禁输出）：
            学习习惯
            薄弱科目
            学习方法
            """;

    /**
     * 注册时初始化空学业档案，同步初始记忆到向量库
     */
    @Transactional
    public void initProfile(Long userId) {
        StudentProfile profile = new StudentProfile();
        profile.setUserId(userId);
        profileMapper.insert(profile);
        syncProfileToVectorStore(userId, null, null, null);
    }

    /**
     * 查询当前用户学业档案
     */
    public ProfileVO getProfile(Long userId) {
        StudentProfile profile = profileMapper.findByUserId(userId);
        if (profile == null) {
            return null;
        }
        return new ProfileVO(
                profile.getProfileId(),
                profile.getWeakSubjects(),
                profile.getExamPlans(),
                profile.getStudyGoals()
        );
    }

    /**
     * 更新学业档案，异步同步向量库（不阻塞用户操作）
     */
    @Transactional
    public void updateProfile(Long userId, ProfileDTO dto) {
        String weakSubjects = dto.getWeakSubjects() != null ? dto.getWeakSubjects() : "";
        String examPlans = dto.getExamPlans() != null ? dto.getExamPlans() : "";
        String studyGoals = dto.getStudyGoals() != null ? dto.getStudyGoals() : "";

        StudentProfile profile = new StudentProfile();
        profile.setUserId(userId);
        profile.setWeakSubjects(weakSubjects);
        profile.setExamPlans(examPlans);
        profile.setStudyGoals(studyGoals);
        profileMapper.updateByUserId(profile);

        // 异步同步档案记忆到向量库（不阻塞保存按钮）
        syncProfileToVectorStoreAsync(userId, weakSubjects, examPlans, studyGoals);

        // 异步更新画像标签
        generateTagsAsync(userId);
    }

    @Async("memoryExtractExecutor")
    public void syncProfileToVectorStoreAsync(Long userId, String weakSubjects, String examPlans, String studyGoals) {
        syncProfileToVectorStore(userId, weakSubjects, examPlans, studyGoals);
    }

    @Async("memoryExtractExecutor")
    public void generateTagsAsync(Long userId) {
        generateTags(userId);
    }

    /**
     * 将学业档案拆解为标准记忆条目，写入向量库（阶段3.4）
     * 先删除旧档案记忆，再写入最新版本。
     */
    private void syncProfileToVectorStore(Long userId, String weakSubjects, String examPlans, String studyGoals) {
        try {
            // 1. 删除旧的档案记忆文档（type=profile）
            deleteOldProfileDocs(userId);

            // 2. 将档案内容拆解为标准记忆条目
            List<String> memoryTexts = formatProfileToMemories(weakSubjects, examPlans, studyGoals);
            if (memoryTexts.isEmpty()) {
                log.debug("用户{}档案为空，跳过向量库同步", userId);
                return;
            }

            // 3. 逐条写入 Chroma + MySQL
            for (String memoryText : memoryTexts) {
                String docId = UUID.randomUUID().toString();
                Document doc = Document.builder()
                        .id(docId)
                        .text(memoryText)
                        .metadata(Map.of("userId", String.valueOf(userId), "type", "profile"))
                        .build();
                vectorStore.add(List.of(doc));

                MemoryRecord record = new MemoryRecord();
                record.setUserId(userId);
                record.setMemoryText(memoryText);
                record.setVectorId(docId);
                memoryRecordMapper.insert(record);
            }
            log.info("用户{}档案记忆同步完成，写入{}条", userId, memoryTexts.size());
        } catch (Exception e) {
            log.error("用户{}档案记忆同步失败: {}", userId, e.getMessage());
            // 静默失败，不阻塞档案更新主流程
        }
    }

    /**
     * 删除用户旧的 type=profile 记忆文档
     */
    private void deleteOldProfileDocs(Long userId) {
        try {
            List<Document> oldDocs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query("学习档案")
                            .topK(50)
                            .filterExpression("userId == '" + userId + "' && type == 'profile'")
                            .build()
            );
            if (!oldDocs.isEmpty()) {
                List<String> docIds = oldDocs.stream().map(Document::getId).collect(Collectors.toList());
                vectorStore.delete(docIds);
                // 同步删除 MySQL 中对应记录
                for (String docId : docIds) {
                    memoryRecordMapper.deleteByVectorId(docId);
                }
                log.debug("用户{}清理旧档案记忆{}条", userId, docIds.size());
            }
        } catch (Exception e) {
            log.warn("清理旧档案记忆失败: {}", e.getMessage());
        }
    }

    /**
     * 将档案内容格式化为标准记忆条目
     * 格式：类别-科目-描述
     */
    private List<String> formatProfileToMemories(String weakSubjects, String examPlans, String studyGoals) {
        List<String> memories = new ArrayList<>();
        if (weakSubjects != null && !weakSubjects.isBlank()) {
            memories.add("薄弱科目-" + weakSubjects.replace(",", "、"));
        }
        if (examPlans != null && !examPlans.isBlank()) {
            memories.add("考试计划-" + examPlans.replace(",", "、"));
        }
        if (studyGoals != null && !studyGoals.isBlank()) {
            memories.add("学习目标-" + studyGoals.replace(",", "、"));
        }
        return memories;
    }

    /**
     * 获取用户学习画像标签（阶段5）
     */
    public List<TagVO> getTags(Long userId) {
        var user = userMapper.findById(userId);
        if (user == null || user.getUserTags() == null || user.getUserTags().isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(user.getUserTags().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> {
                    int idx = s.lastIndexOf('|');
                    if (idx > 0) {
                        try {
                            String name = s.substring(0, idx).trim();
                            int weight = Integer.parseInt(s.substring(idx + 1).trim());
                            return new TagVO(name, Math.max(1, Math.min(5, weight)));
                        } catch (NumberFormatException e) {
                            return new TagVO(s.trim(), 3); // 默认权重3
                        }
                    }
                    return new TagVO(s.trim(), 3);
                })
                .collect(Collectors.toList());
    }

    /**
     * 异步生成用户学习画像标签（阶段5）
     * 触发时机：记忆提取完成、档案修改后
     */
    @Async("memoryExtractExecutor")
    public void generateTags(Long userId) {
        doGenerateTags(userId);
    }

    /**
     * 同步生成标签（手动提取后前端需要立即看到结果）
     */
    public void generateTagsSync(Long userId) {
        doGenerateTags(userId);
    }

    private void doGenerateTags(Long userId) {
        // 频率控制：10分钟内不重复生成，减少 API 调用
        String lockKey = TAG_LOCK_PREFIX + userId;
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", TAG_INTERVAL_SECONDS, TimeUnit.SECONDS);
        if (locked == null || !locked) {
            log.debug("用户{}标签10分钟内已生成过，跳过", userId);
            return;
        }

        try {
            List<String> allMemories = memoryRecordMapper.findByUserId(userId)
                    .stream().map(MemoryRecord::getMemoryText).toList();

            if (allMemories.isEmpty()) {
                log.debug("用户{}无记忆数据，跳过标签生成", userId);
                return;
            }

            String memoryInput = String.join("\n", allMemories);
            String result = chatClient.prompt()
                    .system(TAG_GENERATE_PROMPT)
                    .user(memoryInput)
                    .call()
                    .content();

            if (result != null && !result.isBlank()) {
                // 新格式：标签名|权重，每行一个。存为 标签1|5,标签2|3
                String tags = result.trim().replaceAll("\\s*\n\\s*", ",");
                userMapper.updateTags(userId, tags);
                log.info("用户{}画像标签更新完成: {}", userId, tags);
            }
        } catch (Exception e) {
            String errMsg = e.getMessage() != null ? e.getMessage() : "";
            if (errMsg.contains("429") || errMsg.contains("rate limit") || errMsg.contains("50609")) {
                log.warn("用户{}标签生成触发API限流，跳过", userId);
            } else {
                log.warn("用户{}画像标签生成失败: {}", userId, errMsg);
            }
        }
    }
}
