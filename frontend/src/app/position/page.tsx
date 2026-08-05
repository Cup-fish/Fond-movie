'use client'

import { useState, useEffect, useRef } from 'react'
import { useRouter } from 'next/navigation'
import { ArrowLeft } from 'lucide-react'
import Loading from '@/components/Loading'
import { useHomeStore } from '@/store/home'
import { formatCityList } from '@/lib/utils'
import api from '@/lib/api'
import type { CityGroup } from '@/types'

export default function PositionPage() {
  const router = useRouter()
  const { setPosId, setPosition } = useHomeStore()
  const [cityList, setCityList] = useState<CityGroup[]>([])
  const [loading, setLoading] = useState(true)
  const [activeTag, setActiveTag] = useState('')
  const tagRefs = useRef<Map<string, HTMLDivElement>>(new Map())

  useEffect(() => {
    const cached = localStorage.getItem('maoyan-cities')
    if (cached) {
      try {
        const list = formatCityList(JSON.parse(cached))
        setCityList(list)
        setLoading(false)
        return
      } catch {}
    }

    api
      .getCities()
      .then((res) => {
        const cities = res?.cts || res?.cities || []
        if (cities.length) {
          localStorage.setItem('maoyan-cities', JSON.stringify(cities))
          setCityList(formatCityList(cities))
        }
      })
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [])

  const chooseCity = (name: string, id: number) => {
    setPosition(name)
    setPosId(id)
    router.push('/')
  }

  const scrollToTag = (tag: string) => {
    setActiveTag(tag)
    const el = tagRefs.current.get(tag)
    el?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  const quickNavTags = cityList.map((c) => c.tag)

  if (loading) return <Loading />

  return (
    <div className="min-h-screen bg-canvas-dark">
      {/* Top bar */}
      <div className="bg-surface-card border-b border-hairline-dark min-w-[1200px]">
        <div className="max-w-[1280px] mx-auto h-[60px] flex items-center gap-4 px-4">
          <ArrowLeft
            className="w-6 h-6 text-muted cursor-pointer hover:text-primary transition-colors"
            onClick={() => router.push('/')}
          />
          <h1 className="text-lg font-medium text-body-dark">选择城市</h1>
        </div>
      </div>

      <div className="max-w-[1280px] mx-auto mt-6 flex gap-4 px-4">
        {/* City list */}
        <div className="flex-1 bg-surface-card border border-hairline-dark rounded-lg shadow-card-dark max-h-[70vh] overflow-y-auto">
          {cityList.map((group) => (
            <div
              key={group.tag}
              ref={(el) => {
                if (el) tagRefs.current.set(group.tag, el)
              }}
            >
              <div className="sticky top-0 bg-surface-elevated px-6 py-2 text-sm text-muted font-medium uppercase">
                {group.tag}
              </div>
              <div className="grid grid-cols-4 gap-2 px-6 py-2">
                {group.items.map((city) => (
                  <div
                    key={city.id}
                    className="px-4 py-2 text-sm cursor-pointer text-muted-strong hover:text-primary hover:bg-surface-elevated/50 rounded transition-colors"
                    onClick={() => chooseCity(city.nm, city.id)}
                  >
                    {city.nm}
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>

        {/* Right quick nav */}
        <div className="w-8 flex flex-col items-center justify-center py-2 bg-surface-card border border-hairline-dark rounded-lg shadow-card-dark sticky top-[80px] self-start">
          {quickNavTags.map((tag) => (
            <div
              key={tag}
              className={`text-xs py-0.5 cursor-pointer uppercase ${
                tag === activeTag ? 'text-primary font-bold' : 'text-muted'
              }`}
              onClick={() => scrollToTag(tag)}
            >
              {tag}
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
