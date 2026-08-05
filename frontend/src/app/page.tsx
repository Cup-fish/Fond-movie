'use client'

import { useState } from 'react'
import Header from '@/components/Header'
import Footer from '@/components/Footer'
import HomeTab from '@/components/HomeTab'
import MoviesTab from '@/components/MoviesTab'
import CinemasTab from '@/components/CinemasTab'
import type { Tab } from '@/types'

export default function RootPage() {
  const [activeTab, setActiveTab] = useState<Tab>('home')

  return (
    <div className="min-h-screen bg-canvas-dark text-body-dark font-sans flex flex-col">
      <Header activeTab={activeTab} setActiveTab={setActiveTab} />

      <main className="max-w-[1280px] mx-auto mt-10 min-h-[600px] pb-10 w-full px-4">
        {activeTab === 'home' && <HomeTab />}
        {activeTab === 'movies' && <MoviesTab />}
        {activeTab === 'cinemas' && <CinemasTab />}
      </main>

      <Footer />
    </div>
  )
}
