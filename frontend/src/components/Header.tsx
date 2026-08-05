'use client'

import { useState } from 'react'
import { useRouter } from 'next/navigation'
import { useScroll, useMotionValueEvent, motion } from 'motion/react'
import { ChevronDown, Search, User, Smartphone, Coins } from 'lucide-react'
import { useUserStore } from '@/store/user'
import { useHomeStore } from '@/store/home'
import type { Tab } from '@/types'

interface HeaderProps {
  activeTab: Tab
  setActiveTab: (tab: Tab) => void
}

export default function Header({ activeTab, setActiveTab }: HeaderProps) {
  const router = useRouter()
  const { isLogged, userNick, points, logout } = useUserStore()
  const { position } = useHomeStore()
  const [searchText, setSearchText] = useState('')
  const [showUserMenu, setShowUserMenu] = useState(false)
  const [scrolled, setScrolled] = useState(false)

  // 滚动距离驱动导航背景（motion 高效滚动监听，避免 scroll listener 重渲染）
  const { scrollY } = useScroll()
  useMotionValueEvent(scrollY, 'change', (v) => setScrolled(v > 24))

  const handleSearch = () => {
    if (searchText.trim()) {
      router.push(`/search?kw=${encodeURIComponent(searchText)}`)
    }
  }

  const navItems = [
    { key: 'home', label: '首页' },
    { key: 'movies', label: '电影' },
    { key: 'cinemas', label: '影院' },
  ] as { key: Tab; label: string }[]

  return (
    <header
      className={`sticky top-0 z-50 min-w-[1200px] border-b transition-all duration-300 ${
        scrolled
          ? 'bg-canvas-dark/85 backdrop-blur-md border-hairline-dark'
          : 'bg-canvas-dark border-transparent'
      }`}
    >
      <div className="max-w-[1280px] mx-auto h-16 flex items-center justify-between px-4">
        {/* Left: Logo & Nav */}
        <div className="flex items-center gap-6 h-full">
          {/* Logo — 黄色 wordmark（品牌电压） */}
          <div
            className="flex items-center cursor-pointer gap-2"
            onClick={() => setActiveTab('home')}
          >
            <div className="w-9 h-9 bg-primary rounded-full flex items-center justify-center">
              <span className="text-on-primary font-bold text-lg">猫</span>
            </div>
            <span className="text-xl font-bold tracking-tight text-primary">猫眼电影</span>
          </div>

          {/* City Selector */}
          <div
            className="flex items-center cursor-pointer group"
            onClick={() => router.push('/position')}
          >
            <span className="text-muted-strong text-sm group-hover:text-primary transition-colors">{position}</span>
            <ChevronDown
              size={14}
              className="ml-1 text-muted group-hover:text-primary group-hover:rotate-180 transition-all"
            />
          </div>

          {/* Nav — 激活项黄色下划线 */}
          <nav className="flex h-full gap-2 ml-4">
            {navItems.map((item) => (
              <button
                key={item.key}
                onClick={() => setActiveTab(item.key)}
                className={`relative h-full px-4 text-[15px] font-medium transition-colors ${
                  activeTab === item.key ? 'text-primary' : 'text-muted-strong hover:text-body-dark'
                }`}
              >
                {item.label}
                {activeTab === item.key && (
                  <motion.span
                    layoutId="nav-underline"
                    className="absolute left-4 right-4 bottom-0 h-[2px] bg-primary rounded-full"
                    transition={{ type: 'spring', stiffness: 400, damping: 32 }}
                  />
                )}
              </button>
            ))}
            <button className="px-4 text-[15px] font-medium text-muted-strong hover:text-body-dark transition-colors">
              演出
            </button>
          </nav>
        </div>

        {/* Right: App, Search, User */}
        <div className="flex items-center gap-6">
          <div className="flex items-center text-muted cursor-pointer hover:text-primary transition-colors">
            <Smartphone size={17} className="mr-1.5" />
            <span className="text-sm">APP下载</span>
            <ChevronDown size={14} className="ml-1" />
          </div>

          {/* Search — surface-card 输入 + 黄色按钮 */}
          <div className="relative">
            <input
              type="text"
              placeholder="找影视剧、影人、影院"
              value={searchText}
              onChange={(e) => setSearchText(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
              className="bg-surface-card border border-hairline-dark rounded-lg py-2 pl-4 pr-11 text-sm w-60 text-body-dark placeholder:text-muted focus:outline-none focus:border-primary/60 transition-colors"
            />
            <div
              onClick={handleSearch}
              className="absolute right-1.5 top-1/2 -translate-y-1/2 w-8 h-8 bg-primary rounded-md flex items-center justify-center cursor-pointer pressable hover:bg-primary-active"
            >
              <Search size={15} className="text-on-primary" />
            </div>
          </div>

          {/* User */}
          <div className="relative">
            <div
              className="w-10 h-10 rounded-full bg-surface-elevated border border-hairline-dark flex items-center justify-center cursor-pointer hover:border-primary/60 transition-colors"
              onClick={() => {
                if (isLogged) {
                  setShowUserMenu(!showUserMenu)
                } else {
                  router.push('/login')
                }
              }}
            >
              {isLogged ? (
                <span className="text-primary text-sm font-semibold">
                  {userNick?.[0] || 'U'}
                </span>
              ) : (
                <User size={18} className="text-muted-strong" />
              )}
            </div>

            {/* User dropdown — surface-card 面板 */}
            {showUserMenu && isLogged && (
              <>
                <div
                  className="fixed inset-0 z-40"
                  onClick={() => setShowUserMenu(false)}
                />
                <motion.div
                  initial={{ opacity: 0, y: 6, scale: 0.98 }}
                  animate={{ opacity: 1, y: 0, scale: 1 }}
                  transition={{ duration: 0.18, ease: [0.16, 1, 0.3, 1] }}
                  className="absolute right-0 top-12 z-50 bg-surface-card border border-hairline-dark rounded-lg shadow-card-dark py-2 w-48"
                >
                  <div className="px-4 py-2 text-sm text-body-dark border-b border-hairline-dark">
                    {userNick}
                  </div>
                  <div className="px-4 py-2 text-sm text-muted-strong border-b border-hairline-dark flex items-center gap-1.5">
                    <Coins size={14} className="text-primary" />
                    <span className="font-plex text-primary font-medium">{points}</span> 积分
                  </div>
                  <button
                    className="w-full text-left px-4 py-2 text-sm text-muted-strong hover:text-body-dark hover:bg-surface-elevated transition-colors"
                    onClick={() => { router.push('/orders'); setShowUserMenu(false) }}
                  >
                    我的订单
                  </button>
                  <button
                    className="w-full text-left px-4 py-2 text-sm text-muted-strong hover:text-trading-down hover:bg-surface-elevated transition-colors"
                    onClick={() => {
                      logout()
                      setShowUserMenu(false)
                    }}
                  >
                    退出登录
                  </button>
                </motion.div>
              </>
            )}
          </div>
        </div>
      </div>
    </header>
  )
}
