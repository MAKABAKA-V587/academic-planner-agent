// Markdown 渲染统一配置：highlight.js 语法高亮 + markedHighlight 插件。
// 聊天、资料预览、周报共用同一份配置，避免各页面分别配置导致样式不一致。
import { marked } from 'marked'
import { markedHighlight } from 'marked-highlight'
// highlight.js 按需引入常用语言，减小打包体积
import hljs from 'highlight.js/lib/core'
import javascript from 'highlight.js/lib/languages/javascript'
import typescript from 'highlight.js/lib/languages/typescript'
import java from 'highlight.js/lib/languages/java'
import python from 'highlight.js/lib/languages/python'
import c from 'highlight.js/lib/languages/c'
import cpp from 'highlight.js/lib/languages/cpp'
import sql from 'highlight.js/lib/languages/sql'
import json from 'highlight.js/lib/languages/json'
import xml from 'highlight.js/lib/languages/xml'
import bash from 'highlight.js/lib/languages/bash'
import css from 'highlight.js/lib/languages/css'
import markdown from 'highlight.js/lib/languages/markdown'
import 'highlight.js/styles/github-dark.min.css'

hljs.registerLanguage('javascript', javascript)
hljs.registerLanguage('typescript', typescript)
hljs.registerLanguage('java', java)
hljs.registerLanguage('python', python)
hljs.registerLanguage('c', c)
hljs.registerLanguage('cpp', cpp)
hljs.registerLanguage('sql', sql)
hljs.registerLanguage('json', json)
hljs.registerLanguage('xml', xml)
hljs.registerLanguage('bash', bash)
hljs.registerLanguage('shell', bash)
hljs.registerLanguage('css', css)
hljs.registerLanguage('markdown', markdown)

marked.use(markedHighlight({
  langPrefix: 'hljs language-',
  highlight(code, lang) {
    // mermaid 图表源码不做 hljs 高亮：highlightAuto 会把 A[链表反转] 误识别为
    // CSS 选择器并插入 span 标签，污染 mermaid 语法导致渲染失败
    if (lang && lang.toLowerCase() === 'mermaid') return code
    // 清理混入代码块的 hljs 渲染痕迹（渲染后的 HTML 被粘贴回文档、又被引用时）
    let cleaned = code
    if (/<\/?span[^>]*>|&(?:lt|gt|amp|quot|#39|#x27|#34|#x22|#60|#x3C|#62|#x3E|#38|#x26);/i.test(code)) {
      // 递归解码实体 + 移除 hljs span，直到文本不再变化（处理多层嵌套转义，最多10层防死循环）
      let prev = null
      let depth = 0
      while (prev !== cleaned && depth++ < 10) {
        prev = cleaned
        cleaned = cleaned
          .replace(/&(lt|gt|amp|quot|#39|#x27|#34|#x22|#60|#x3C|#62|#x3E|#38|#x26);/gi,
            (_, n) => ({ lt: '<', gt: '>', amp: '&', quot: '"', '#39': "'", '#x27': "'",
                         '#34': '"', '#x22': '"', '#60': '<', '#x3C': '<',
                         '#62': '>', '#x3E': '>', '#38': '&', '#x26': '&' }[n.toLowerCase()]))
          // 移除任何含 hljs 样式的标签（含解码后拼出的残缺形式，如 <class="hljs-keyword">）及闭合 span
          // [^<>]* 不跨嵌套，避免贪婪吃掉标签间内容
          .replace(/<[^<>]*class="hljs-[^"]*"[^<>]*>|<\/span>/g, '')
      }
      // 剥离残留的孤立角括号碎片（如 <GET），保留 "a < b" 这类带空格的比较写法
      cleaned = cleaned.replace(/<(?=\S)/g, '').replace(/>(?=\S)/g, '')
    }
    if (lang && hljs.getLanguage(lang)) {
      return hljs.highlight(cleaned, { language: lang }).value
    }
    return hljs.highlightAuto(cleaned).value
  }
}))

export { marked, hljs }
