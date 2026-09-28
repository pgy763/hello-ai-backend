import { defineConfig } from 'vitepress'

export default defineConfig({
  lang: 'zh-CN',
  title: 'Hello, AI Backend',
  description: '写给 Java 后端工程师的 AI 接入手册',
  base: '/hello-ai-backend/',
  cleanUrls: true,
  lastUpdated: true,
  head: [['meta', { name: 'theme-color', content: '#0F6E56' }]],

  themeConfig: {
    nav: [
      { text: '开始阅读', link: '/guide/00-intro' },
      { text: '全书目录', link: '/guide/' },
      { text: '踩坑手册', link: 'https://github.com/pgy763/llm-dev-pitfalls' },
      { text: 'GitHub', link: 'https://github.com/pgy763/hello-ai-backend' }
    ],

    sidebar: [
      {
        text: '序章',
        items: [{ text: '写给谁，以及为什么不是 Python 教程', link: '/guide/00-intro' }]
      },
      {
        text: '第一部分 · 起步',
        collapsed: false,
        items: [
          { text: '1. 选型：Spring AI vs LangChain4j vs 裸 HTTP', link: '/guide/01-selection' },
          { text: '2. 第一个请求：SSE 流式响应', link: '/guide/02-first-request' },
          { text: '3. 统一多模型接入', link: '/guide/03-multi-provider' }
        ]
      },
      {
        text: '第二部分 · 生产必备的五个机制',
        collapsed: false,
        items: [
          { text: '4. 虚拟密钥：多租户与额度', link: '/guide/04-virtual-key' },
          { text: '5. 降级链：主备模型自动切换', link: '/guide/05-fallback' },
          { text: '6. 熔断与限流：Redis + Lua 令牌桶', link: '/guide/06-ratelimit' },
          { text: '7. 多步任务编排：状态机与断点续跑', link: '/guide/07-state-machine' },
          { text: '8. 可观测性：token 计量与成本核算', link: '/guide/08-observability' }
        ]
      },
      {
        text: '第三部分 · 让 AI 读懂你的数据',
        collapsed: false,
        items: [
          { text: '9. RAG 落地：向量库与切分策略', link: '/guide/09-rag-basics' },
          { text: '10. 把 RAG 接进 Java 服务', link: '/guide/10-rag-in-java' }
        ]
      },
      {
        text: '第四部分 · 收尾',
        collapsed: false,
        items: [
          { text: '11. 压测与容量规划', link: '/guide/11-capacity' },
          { text: '12. 生产事故复盘集', link: '/guide/12-postmortem' },
          { text: '13. 面试题：AI 后端 30 问', link: '/guide/13-interview' },
          { text: '14. 毕业设计', link: '/guide/14-capstone' }
        ]
      }
    ],

    search: { provider: 'local' },
    outline: { level: [2, 3], label: '本页目录' },
    docFooter: { prev: '上一章', next: '下一章' },
    darkModeSwitchLabel: '主题',
    sidebarMenuLabel: '目录',
    returnToTopLabel: '回到顶部',
    lastUpdatedText: '最后更新',
    langMenuLabel: '语言',
    footer: {
      message: '代码 MIT · 文档 CC BY-NC-SA 4.0',
      copyright: 'Copyright © 2026 蒲公英 (pgy763)'
    }
  }
})
