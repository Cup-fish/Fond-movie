import type { Metadata } from 'next'
import { Inter, JetBrains_Mono } from 'next/font/google'
import { Toaster } from 'sonner'
import './globals.css'

/**
 * 字体栈 — BinanceNova → Inter（开源替代）；BinancePlex → JetBrains Mono（数字）
 */
const inter = Inter({
  subsets: ['latin'],
  variable: '--font-inter',
  display: 'swap',
})

const plex = JetBrains_Mono({
  subsets: ['latin'],
  variable: '--font-plex',
  display: 'swap',
})

export const metadata: Metadata = {
  title: '猫眼电影 - 娱乐看猫眼',
  description: '仿猫眼电影PC端 - Next.js 14',
}

export default function RootLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <html lang="zh-CN" className={`${inter.variable} ${plex.variable}`}>
      <body className="bg-canvas-dark min-h-screen font-sans text-body-dark" suppressHydrationWarning>
        {children}
        <Toaster position="top-center" richColors theme="dark" />
      </body>
    </html>
  )
}
