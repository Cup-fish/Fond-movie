'use client'

import { useEffect, useState } from 'react'
import { ArrowUp } from 'lucide-react'
import { useReducedMotion } from 'motion/react'

/**
 * 返回顶部按钮 — 滚动超过一定距离后出现，平滑回到顶部。
 * 遵循 prefers-reduced-motion：直接跳转，不播放平滑滚动。
 */
export default function BackToTop() {
  const [visible, setVisible] = useState(false)
  const reduce = useReducedMotion()

  useEffect(() => {
    const onScroll = () => setVisible(window.scrollY > 400)
    onScroll()
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [])

  const scrollTop = () => {
    if (reduce) {
      window.scrollTo(0, 0)
    } else {
      window.scrollTo({ top: 0, behavior: 'smooth' })
    }
  }

  return (
    <button
      type="button"
      aria-label="返回顶部"
      onClick={scrollTop}
      className={`fixed bottom-6 right-6 z-50 flex h-11 w-11 items-center justify-center rounded-full border border-hairline-dark bg-surface-card text-primary shadow-card-dark transition-all duration-300 hover:border-primary/60 hover:bg-surface-elevated focus:outline-none focus-visible:ring-2 focus-visible:ring-primary/60 ${
        visible ? 'translate-y-0 opacity-100' : 'pointer-events-none translate-y-4 opacity-0'
      }`}
    >
      <ArrowUp size={18} />
    </button>
  )
}
