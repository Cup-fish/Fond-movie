'use client'

import { Suspense, useCallback, useEffect, useState } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'
import Header from '@/components/Header'
import Footer from '@/components/Footer'
import HomeTab from '@/components/HomeTab'
import MoviesTab from '@/components/MoviesTab'
import CinemasTab from '@/components/CinemasTab'
import PageTransition from '@/components/motion/PageTransition'
import type { Tab } from '@/types'

function parseTab(value: string | null): Tab {
  return value === 'movies' || value === 'cinemas' ? value : 'home'
}

function RootPage() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const [activeTab, setActiveTab] = useState<Tab>(() => parseTab(searchParams.get('tab')))

  // 浏览器前进/后退时同步 Tab
  useEffect(() => {
    setActiveTab(parseTab(searchParams.get('tab')))
  }, [searchParams])

  const changeTab = useCallback((tab: Tab) => {
    setActiveTab(tab)
    router.replace(`/?tab=${tab}`, { scroll: false })
  }, [router])

  return (
    <div className="min-h-screen bg-canvas-dark text-body-dark font-sans flex flex-col">
      <Header activeTab={activeTab} setActiveTab={changeTab} />

      <main className="relative max-w-[1280px] mx-auto mt-10 min-h-[600px] pb-10 w-full px-4">
        {/* 顶部环境光：极淡的黄色辉光，增加深色页面层次感 */}
        <div className="pointer-events-none absolute inset-x-0 -top-10 h-72 bg-[radial-gradient(60%_60%_at_50%_0%,rgba(252,213,53,0.06),transparent_70%)]" />
        <div className="relative">
          <PageTransition transitionKey={activeTab}>
            {activeTab === 'home' && <HomeTab />}
            {activeTab === 'movies' && <MoviesTab />}
            {activeTab === 'cinemas' && <CinemasTab />}
          </PageTransition>
        </div>
      </main>

      <Footer />
    </div>
  )
}

export default function Page() {
  return (
    <Suspense fallback={<div className="min-h-screen bg-canvas-dark" />}>
      <RootPage />
    </Suspense>
  )
}
