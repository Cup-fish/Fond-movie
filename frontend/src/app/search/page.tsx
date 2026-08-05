'use client'

import { Suspense, useState, useCallback } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'
import { Search, X, ArrowLeft } from 'lucide-react'
import { useHomeStore } from '@/store/home'
import Loading from '@/components/Loading'
import api from '@/lib/api'

function SearchContent() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const { posId } = useHomeStore()
  const [keywords, setKeywords] = useState(searchParams.get('kw') || '')
  const [results, setResults] = useState<any[]>([])
  const [timer, setTimer] = useState<ReturnType<typeof setTimeout> | null>(null)

  const handleInput = useCallback(
    (value: string) => {
      setKeywords(value)
      if (timer) clearTimeout(timer)

      if (!value.trim()) {
        setResults([])
        return
      }

      const t = setTimeout(() => {
        const cityId = posId ?? 1
        api
          .search({ kw: value, cityId, stype: 2 })
          .then((res) => {
            const cinemaList = res?.cinemas?.list || []
            const movieList = res?.movies?.list || []
            setResults([...cinemaList, ...movieList])
          })
          .catch(() => {})
      }, 300)
      setTimer(t)
    },
    [posId, timer]
  )

  return (
    <div className="min-h-screen bg-canvas-dark">
      {/* Top bar */}
      <div className="bg-surface-card border-b border-hairline-dark min-w-[1200px]">
        <div className="max-w-[1280px] mx-auto h-[60px] flex items-center gap-4 px-4">
          <ArrowLeft
            className="w-6 h-6 text-muted cursor-pointer hover:text-primary transition-colors"
            onClick={() => router.push('/')}
          />
          <div className="flex-1 flex items-center bg-surface-elevated border border-hairline-dark rounded-lg px-4 py-2 max-w-[600px] focus-within:border-primary/60 transition-colors">
            <Search className="w-5 h-5 text-muted mr-2 flex-shrink-0" />
            <input
              type="text"
              placeholder="搜索电影、影院"
              value={keywords}
              onChange={(e) => handleInput(e.target.value)}
              className="flex-1 bg-transparent outline-none text-sm text-body-dark placeholder:text-muted"
              autoFocus
            />
            {keywords && (
              <X
                className="w-4 h-4 text-muted cursor-pointer hover:text-primary transition-colors"
                onClick={() => {
                  setKeywords('')
                  setResults([])
                }}
              />
            )}
          </div>
        </div>
      </div>

      {/* Results */}
      <div className="max-w-[1280px] mx-auto mt-6 px-4">
        {results.length === 0 && keywords && (
          <div className="text-center py-20 text-muted">
            未找到"{keywords}"相关结果
          </div>
        )}
        <div className="bg-surface-card border border-hairline-dark rounded-lg overflow-hidden">
          {results.map((item: any, i: number) => (
            <div
              key={item.id || i}
              className="py-4 px-6 border-b border-hairline-dark last:border-0 text-sm text-muted-strong hover:bg-surface-elevated/50 hover:text-body-dark cursor-pointer transition-colors"
            >
              {item.nm || item.name}
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

export default function SearchPage() {
  return (
    <Suspense fallback={<Loading />}>
      <SearchContent />
    </Suspense>
  )
}
