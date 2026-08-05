import type { Config } from 'tailwindcss'

/**
 * 设计系统 token — 基于 DESIGN.md（Binance 设计系统）
 *
 * 单一主色：币安黄 #fcd535（只用于主 CTA/品牌强调/高亮数字，稀缺性即力量）
 * 双主题：主站深色（canvas-dark）· 交易面浅色（canvas-light）
 * 语义色：trading-up/down 仅作涨跌/评分方向信号，不作通用成功/错误
 * 圆角：按钮 6px · 输入/卡片 8px · 大容器 12px · 主 CTA pill
 */
const config: Config = {
  content: ['./src/**/*.{js,ts,jsx,tsx,mdx}'],
  theme: {
    extend: {
      colors: {
        // ===== 品牌主色（单一 accent） =====
        primary: '#fcd535',          // Binance Yellow
        'primary-active': '#f0b90b', // hover/pressed
        'primary-disabled': '#3a3a1f',
        'on-primary': '#181a20',     // 黄底黑字（品牌签名组合）
        gold: '#fcd535',             // 兼容旧引用

        // ===== 画布 / 表面 =====
        canvas: {
          dark: '#0b0e11',
          light: '#ffffff',
        },
        surface: {
          card: '#1e2329',     // 深色卡片
          elevated: '#2b3139', // 深色悬浮层 / hover
          soft: '#fafafa',     // 浅色 footer
          strong: '#f5f5f5',   // 浅色输入底色
        },
        // ===== 文字 =====
        ink: '#181a20',
        'body-dark': '#eaecef',
        'body-light': '#181a20',
        muted: '#707a8a',
        'muted-strong': '#929aa5',

        // ===== 描边 =====
        hairline: {
          dark: '#2b3139',
          light: '#eaecef',
        },
        'border-strong': '#cdd1d6',

        // ===== 交易语义色 =====
        'trading-up': '#0ecb81',   // 评分↑ / 价格涨
        'trading-down': '#f6465d', // 评分↓ / 价格跌

        // ===== 信息色（焦点环/服务标签） =====
        info: '#3b82f6',
        'on-dark': '#ffffff',

        // ===== 旧兼容色 =====
        secondary: '#2dbdb6', // accent-turquoise（仅极少量点缀）
      },
      borderRadius: {
        xs: '2px',
        sm: '4px',
        md: '6px',
        lg: '8px',
        xl: '12px',
      },
      fontFamily: {
        sans: ['var(--font-inter)', '-apple-system', 'BlinkMacSystemFont', 'PingFang SC', 'sans-serif'],
        // BinancePlex 替代：所有数字/价格用等宽保证表格一致性
        plex: ['var(--font-plex)', 'ui-monospace', 'SFMono-Regular', 'monospace'],
      },
      boxShadow: {
        'card-dark': '0 2px 12px rgba(0,0,0,0.35)',
        'card-light': '0 2px 12px rgba(24,26,32,0.08)',
        focus: '0 0 0 2px rgba(59,130,246,0.5)',
      },
      keyframes: {
        shimmer: {
          '0%': { backgroundPosition: '-200% 0' },
          '100%': { backgroundPosition: '200% 0' },
        },
        'pulse-dot': {
          '0%, 100%': { opacity: '1', transform: 'scale(1)' },
          '50%': { opacity: '0.4', transform: 'scale(0.85)' },
        },
        marquee: {
          '0%': { transform: 'translateX(0)' },
          '100%': { transform: 'translateX(-50%)' },
        },
        'fade-up': {
          '0%': { opacity: '0', transform: 'translateY(16px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        'scan-sweep': {
          '0%': { transform: 'translateY(-100%)' },
          '100%': { transform: 'translateY(100%)' },
        },
      },
      animation: {
        shimmer: 'shimmer 1.6s linear infinite',
        'pulse-dot': 'pulse-dot 1.2s ease-in-out infinite',
        marquee: 'marquee 24s linear infinite',
        'fade-up': 'fade-up 0.5s cubic-bezier(0.16,1,0.3,1) both',
        'scan-sweep': 'scan-sweep 2.4s ease-in-out infinite',
      },
    },
  },
  plugins: [],
}

export default config
