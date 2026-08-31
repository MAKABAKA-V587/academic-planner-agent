# ============================================================
# student-agent 后端一键回归测试脚本 (PowerShell 5.1)
# 用法: powershell -ExecutionPolicy Bypass -File test-suite.ps1
# 前置: 后端已启动 (mvn spring-boot:run) / Chroma:8000 / Redis:6379
# ============================================================
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$BASE  = 'http://localhost:8080'
$USER  = 'kaoyan_zhang'
$USER_PASS = '123456'

$script:passCount = 0
$script:failCount = 0

function New-TempJson($json) {
    $tmp = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($tmp, $json, (New-Object System.Text.UTF8Encoding $false))
    return $tmp
}

function Add-AuthArgs($argList, $headers) {
    foreach ($k in $headers.Keys) {
        $argList += '-H'; $argList += ('{0}: {1}' -f $k, $headers[$k])
    }
    return $argList
}

# 注意：PS 5.1 的 Invoke-RestMethod 对无 charset 的 application/json 响应按 Latin-1 解码，
# 中文会乱码。因此统一改用 curl.exe 传输原始字节 + ConvertFrom-Json 解析。
function Post-Json($uri, $json, $headers) {
    $tmp = New-TempJson $json
    $argList = @('-s', '-X', 'POST', $uri, '-H', 'Content-Type: application/json')
    $argList = Add-AuthArgs $argList $headers
    $argList += '--data-binary', "@$tmp"
    $raw = & curl.exe @argList 2>$null
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    if (-not $raw) { return $null }
    return ($raw | ConvertFrom-Json)
}

function Get-WithAuth($uri, $headers) {
    $argList = @('-s', '-X', 'GET', $uri)
    $argList = Add-AuthArgs $argList $headers
    $raw = & curl.exe @argList 2>$null
    if (-not $raw) { return $null }
    return ($raw | ConvertFrom-Json)
}

function Post-Stream($uri, $json, $headers) {
    $tmp = New-TempJson $json
    $argList = @('-s', '-X', 'POST', $uri, '-H', 'Content-Type: application/json')
    $argList = Add-AuthArgs $argList $headers
    $argList += '--data-binary', "@$tmp"
    $raw = & curl.exe @argList 2>$null
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    if (-not $raw) { return '' }
    return ($raw -join "`n")
}

function Test-Case($name, $ok, $detail) {
    if ($ok) { $script:passCount++; $c = 'Green' } else { $script:failCount++; $c = 'Red' }
    $mark = if ($ok) { '[PASS]' } else { '[FAIL]' }
    Write-Host ("{0} {1}  {2}" -f $mark, $name, $detail) -ForegroundColor $c
}

Write-Host "========== student-agent 回归测试开始 ==========" -ForegroundColor Cyan

# ---- A. 基础与鉴权 ----
try {
    $h = Invoke-RestMethod -Uri "$BASE/api/health" -TimeoutSec 10
    Test-Case 'A1 健康检查' ($true) "code=$($h.code)"
} catch { Test-Case 'A1 健康检查' $false $_.Exception.Message }

$H = @{}
try {
    $loginJson = '{"username":"' + $USER + '","password":"' + $USER_PASS + '"}'
    $login = Post-Json "$BASE/api/user/login" $loginJson $H
    $token = $login.data.token
    $script:H = @{ Authorization = "Bearer $token" }
    Test-Case 'A2 登录' ($login.code -eq 200 -and $token) "userId=$($login.data.userId)"
} catch { Test-Case 'A2 登录' $false $_.Exception.Message }

try {
    Invoke-RestMethod -Uri "$BASE/api/sessions" -Method Get -TimeoutSec 10 | Out-Null
    Test-Case 'A3 无token访问受保护接口' $false '竟然通过了'
} catch {
    Test-Case 'A3 无token访问受保护接口' ($_.Exception.Response.StatusCode.value__ -eq 401) "Status=$($_.Exception.Response.StatusCode)"
}

if (-not $token) { Write-Host '登录失败，中止测试' -ForegroundColor Red; exit 1 }

# ---- B. 会话 ----
$sid = $null
try {
    $s = Post-Json "$BASE/api/session" '{"title":"自动回归测试"}' $script:H
    $sid = $s.data.sessionId
    Test-Case 'B1 创建会话' ($null -ne $sid) "sessionId=$sid"
} catch { Test-Case 'B1 创建会话' $false $_.Exception.Message }

try {
    $sessions = Get-WithAuth "$BASE/api/sessions" $script:H
    $found = ($sessions.data | Where-Object { $_.sessionId -eq $sid }) -ne $null
    Test-Case 'B2 会话列表' $found '列表包含新会话'
} catch { Test-Case 'B2 会话列表' $false $_.Exception.Message }

# ---- C. 阻塞聊天（记忆召回） ----
try {
    $chatJson = '{"sessionId":' + $sid + ',"message":"根据我的学习习惯，帮我制定今天的学习计划"}'
    $r = Post-Json "$BASE/api/chat" $chatJson $script:H
    $content = [string]$r.data.content
    $ok = $content -match '学习习惯|数学|英语|政治|考研|计划'
    Test-Case 'C1 阻塞聊天-记忆召回' $ok ("len=$($content.Length) 含特征词=$ok")
} catch { Test-Case 'C1 阻塞聊天-记忆召回' $false $_.Exception.Message }

# ---- D. 工具调用 ----
try {
    $json = '{"sessionId":' + $sid + ',"message":"帮我制定一周的考研数学复习计划"}'
    $r = Post-Json "$BASE/api/chat" $json $script:H
    $c = [string]$r.data.content
    Test-Case 'D1 工具-生成学习计划' ($c -match '计划|复习|数学') ("len=$($c.Length)")
} catch { Test-Case 'D1 工具-生成学习计划' $false $_.Exception.Message }

try {
    $json = '{"sessionId":' + $sid + ',"message":"我今天有什么学习安排？"}'
    $r = Post-Json "$BASE/api/chat" $json $script:H
    $c = [string]$r.data.content
    Test-Case 'D2 工具-查询今日安排' ($c -match '任务|安排|日程|计划|没有') ("len=$($c.Length)")
} catch { Test-Case 'D2 工具-查询今日安排' $false $_.Exception.Message }

try {
    $json = '{"sessionId":' + $sid + ',"message":"什么是链表？"}'
    $r = Post-Json "$BASE/api/chat" $json $script:H
    $c = [string]$r.data.content
    Test-Case 'D7 工具-知识库检索' ($c.Length -gt 20) ("len=$($c.Length) 知识来源=$($c -match '知识来源')")
} catch { Test-Case 'D7 工具-知识库检索' $false $_.Exception.Message }

# ---- C4/C5/C6 流式 ----
try {
    $json = '{"sessionId":' + $sid + ',"message":"帮我总结一下今天的复习重点"}'
    $raw = Post-Stream "$BASE/api/chat/stream" $json $script:H
    $ok = $raw.EndsWith('[DONE]') -and -not $raw.Contains('[ERROR]')
    Test-Case 'C4 流式-无工具' $ok ("len=$($raw.Length) [DONE]=$($raw.EndsWith('[DONE]'))")
} catch { Test-Case 'C4 流式-无工具' $false $_.Exception.Message }

try {
    $json = '{"sessionId":' + $sid + ',"message":"帮我把明天的学习计划整理出来"}'
    $raw = Post-Stream "$BASE/api/chat/tool-stream" $json $script:H
    $ok = $raw.EndsWith('[DONE]') -and -not $raw.Contains('[ERROR]')
    Test-Case 'C5 流式-带工具' $ok ("len=$($raw.Length) [DONE]=$($raw.EndsWith('[DONE]'))")
} catch { Test-Case 'C5 流式-带工具' $false $_.Exception.Message }

try {
    $json = '{"sessionId":' + $sid + ',"message":"重新生成测试"}'
    $raw = Post-Stream "$BASE/api/chat/regenerate/stream" $json $script:H
    $ok = $raw.EndsWith('[DONE]') -and -not $raw.Contains('[ERROR]')
    Test-Case 'C6 流式-重新生成' $ok ("len=$($raw.Length) [DONE]=$($raw.EndsWith('[DONE]'))")
} catch { Test-Case 'C6 流式-重新生成' $false $_.Exception.Message }

# ---- B3 消息历史 ----
try {
    $msgs = Get-WithAuth "$BASE/api/session/$sid/messages" $script:H
    $cnt = @($msgs.data).Count
    Test-Case 'B3 消息历史' ($cnt -ge 2) "消息数=$cnt"
} catch { Test-Case 'B3 消息历史' $false $_.Exception.Message }

# ---- E. 记忆/画像/日历/统计/周报 ----
try {
    $r = Post-Json "$BASE/api/memory/extract" '{}' $script:H
    Test-Case 'E1 手动记忆提取' ($r.code -eq 200) "accepted=$($r.data.accepted)"
} catch { Test-Case 'E1 手动记忆提取' $false $_.Exception.Message }

try {
    $r = Post-Json "$BASE/api/test-data/sync-memories/11" '{}' $script:H
    Test-Case 'E2 同步记忆到向量库' ($r.code -eq 200) "synced=$($r.data.synced) skipped=$($r.data.skipped)"
} catch { Test-Case 'E2 同步记忆到向量库' $false $_.Exception.Message }

try {
    $r = Get-WithAuth "$BASE/api/profile/tags" $script:H
    $cnt = @($r.data).Count
    Test-Case 'E3 画像标签' ($cnt -ge 1) "标签数=$cnt"
} catch { Test-Case 'E3 画像标签' $false $_.Exception.Message }

try {
    $r = Get-WithAuth "$BASE/api/calendar/today" $script:H
    Test-Case 'E5 今日日历' ($r.code -eq 200) "事件数=$(@($r.data).Count)"
} catch { Test-Case 'E5 今日日历' $false $_.Exception.Message }

try {
    $r = Get-WithAuth "$BASE/api/statistics/overview" $script:H
    Test-Case 'E6 学习统计' ($r.code -eq 200) "ok"
} catch { Test-Case 'E6 学习统计' $false $_.Exception.Message }

try {
    $r = Post-Json "$BASE/api/report/weekly" '{}' $script:H
    $c = [string]$r.data.content
    Test-Case 'E4 周报生成' ($c.Length -gt 20) "len=$($c.Length)"
} catch { Test-Case 'E4 周报生成' $false $_.Exception.Message }

try {
    $r = Get-WithAuth "$BASE/api/report/weekly/list" $script:H
    Test-Case 'E7 周报列表' ($r.code -eq 200) "历史周报数=$(@($r.data).Count)"
} catch { Test-Case 'E7 周报列表' $false $_.Exception.Message }

# ---- F. 向量库 ----
try {
    $t = [System.Uri]::EscapeDataString('学习习惯-作息-每天6点起床背单词')
    $r = Get-WithAuth ("$BASE/api/test-data/debug-embed?text=" + $t + "&userId=11") $script:H
    $top = @($r.data.matches)[0]
    $ok = ($top -ne $null) -and ($top.score -ge 0.8)
    Test-Case 'F2 向量召回-过滤用户11' $ok ("top1={0:F4}" -f $(if($top){$top.score}else{0}))
} catch { Test-Case 'F2 向量召回-过滤用户11' $false $_.Exception.Message }

# ---- 汇总 ----
Write-Host "========== 测试结束: PASS=$($script:passCount) FAIL=$($script:failCount) ==========" -ForegroundColor Cyan
if ($script:failCount -gt 0) { exit 1 } else { exit 0 }
