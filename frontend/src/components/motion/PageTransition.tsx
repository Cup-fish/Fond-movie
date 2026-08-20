'use client'

import { motion, useReducedMotion } from 'motion/react'
import type { ReactNode } from 'react'

/**
 * 页面/模块切换过渡 — 淡入 + 轻微上移，让 Tab 或路由切换有连续感。
 * 遵循 prefers-reduced-motion：直接显示。
 */
export default function PageTransition({
  children,
  transitionKey,
}: {
  children: ReactNode
  transitionKey?: string | number
}) {
  const reduce = useReducedMotion()

  return (
    <motion.div
      key={transitionKey}
      initial={reduce ? false : { opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.35, ease: [0.16, 1, 0.3, 1] }}
    >
      {children}
    </motion.div>
  )
}
