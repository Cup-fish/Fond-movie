'use client'

import { useState, useEffect, useCallback } from 'react'
import { useRouter } from 'next/navigation'
import { useHomeStore } from '@/store/home'
import { CinemaRowSkeleton } from '@/components/Skeleton'
import api from '@/lib/api'
import { formatDate } from '@/lib/utils'
import type { CinemaItem } from '@/types'

const BRAND_OPTIONS = ['全部', '万达影城', 'CGV影城', '星美国际', '金逸影城', '大地影院', '百老汇影城', '博纳国际', '中影国际', '横店影城', '耀莱成龙']
const DISTRICT_OPTIONS = ['全部', '朝阳区', '海淀区', '东城区', '西城区', '丰台区', '通州区']
const HALL_TYPE_OPTIONS = ['全部', 'IMAX厅', '杜比全景声厅', '4DX厅', '中国巨幕厅', '激光厅', '杜比影院']
const SERVICE_OPTIONS = ['全部', '可退票', '可改签']

function normalizeFilterName(name: string): string {
  return name.replace(/厅|影院|影城|可/g, '').trim()
}

function findIdByNormalizedName(items: any[] | undefined, name: string): number | undefined {
  if (!items) return undefined
  const target = normalizeFilterName(name)
  for (const item of items) {
    if (item?.name == null) continue
    const current = normalizeFilterName(item.name)
    if (current === target || current.includes(target) || target.includes(current)) {
      if (item.id != null && item.id > 0) return item.id
    }
  }
  return undefined
}

function findDistrictId(node: any, name: string): number | undefined {
  if (!node) return undefined
  if (node.name === name && node.id != null && node.id > 0) return node.id
  if (Array.isArray(node.subItems)) {
    for (const child of node.subItems) {
      const id = findDistrictId(child, name)
      if (id != null) return id
    }
  }
  return undefined
}

function FilterRow({
  label,
  options,
  active = '全部',
  onSelect,
}: {
  label: string
  options: string[]
  active?: string
  onSelect?: (v: string) => void
}) {
  return (
    <div className="flex items-start py-3 border-b border-dashed border-hairline-dark last:border-0">
      <span className="text-muted w-20 text-sm shrink-0 mt-0.5">
        {label}
      </span>
      <div className="flex flex-wrap gap-3">
        {options.map((opt) => (
          <span
            key={opt}
            onClick={() => onSelect?.(opt)}
            className={`px-3 py-0.5 text-sm rounded-full cursor-pointer transition-all pressable ${
              opt === active
                ? 'bg-primary text-on-primary font-medium'
                : 'text-muted-strong hover:text-primary'
            }`}
          >
            {opt}
          </span>
        ))}
      </div>
    </div>
  )
}

export default function CinemasTab() {
  const router = useRouter()
  const { posId, setPosId, setPosition } = useHomeStore()
  const [cinemas, setCinemas] = useState<CinemaItem[]>([])
  const [filterOptions, setFilterOptions] = useState<any>(null)
  const [loading, setLoading] = useState(true)
  const [activeBrand, setActiveBrand] = useState('全部')
  const [activeDistrict, setActiveDistrict] = useState('全部')
  const [activeHallType, setActiveHallType] = useState('全部')
  const [activeService, setActiveService] = useState('全部')

  const cityId = posId ?? 1

  const fetchFilterOptions = useCallback(async () => {
    try {
      const res = await api.filterCinemas({ ci: cityId })
      setFilterOptions(res.data || res)
    } catch {
      // 筛选项加载失败不阻塞影院列表
    }
  }, [cityId])

  const fetchCinemas = useCallback(async () => {
    setLoading(true)
    const params: any = {
      cityId,
      day: formatDate(),
    }

    if (activeBrand !== '全部' && filterOptions?.brand) {
      const brandId = findIdByNormalizedName(filterOptions.brand, activeBrand)
      if (brandId) params.brandId = brandId
    }
    if (activeDistrict !== '全部' && filterOptions?.district) {
      const districtId = findDistrictId(filterOptions.district, activeDistrict)
      if (districtId) params.districtId = districtId
    }
    if (activeHallType !== '全部' && filterOptions?.hallType?.subItems) {
      const hallTypeId = findIdByNormalizedName(filterOptions.hallType.subItems, activeHallType)
      if (hallTypeId) params.hallType = hallTypeId
    }
    if (activeService !== '全部' && filterOptions?.service?.subItems) {
      const serviceId = findIdByNormalizedName(filterOptions.service.subItems, activeService)
      if (serviceId) params.serviceId = serviceId
    }

    try {
      const res = await api.getCinemaList(params)
      const list = res?.cinemas || res?.data?.cinemas || []
      setCinemas(list)
    } catch {
      setCinemas([])
    } finally {
      setLoading(false)
    }
  }, [cityId, activeBrand, activeDistrict, activeHallType, activeService, filterOptions])

  // 初始化：加载筛选项 + 影院列表
  useEffect(() => {
    fetchFilterOptions()
  }, [fetchFilterOptions])

  // 筛选条件或城市变化时重新拉取
  useEffect(() => {
    fetchCinemas()
  }, [fetchCinemas])

  return (
    <div>
      {/* Filters */}
      <div className="border border-hairline-dark bg-surface-card p-5 mb-8 text-sm rounded-lg">
        <FilterRow
          label="品牌："
          options={BRAND_OPTIONS}
          active={activeBrand}
          onSelect={setActiveBrand}
        />
        <FilterRow
          label="行政区："
          options={DISTRICT_OPTIONS}
          active={activeDistrict}
          onSelect={setActiveDistrict}
        />
        <FilterRow
          label="影厅类型："
          options={HALL_TYPE_OPTIONS}
          active={activeHallType}
          onSelect={setActiveHallType}
        />
        <FilterRow
          label="影院服务："
          options={SERVICE_OPTIONS}
          active={activeService}
          onSelect={setActiveService}
        />
      </div>

      {/* Header */}
      <div className="flex items-center mb-6 pl-4 border-l-[3px] border-primary">
        <h2 className="text-xl font-semibold tracking-tight text-body-dark">影院列表</h2>
      </div>

      {/* Cinema List */}
      {loading ? (
        <div className="space-y-0">
          {Array.from({ length: 6 }).map((_, i) => <CinemaRowSkeleton key={i} />)}
        </div>
      ) : cinemas.length === 0 ? (
        <div className="text-center py-20">
          <p className="text-muted mb-4">当前城市暂无影院数据，或筛选条件没有匹配结果</p>
          <button
            onClick={() => {
              setPosId(1)
              setPosition('北京')
            }}
            className="px-6 py-2.5 bg-primary text-on-primary rounded-md text-sm font-medium hover:bg-primary-active transition-colors pressable"
          >
            切换到北京演示数据
          </button>
        </div>
      ) : (
        <div className="space-y-0">
          {cinemas.map((cinema) => {
            const tags: { text: string; type: 'blue' | 'orange' }[] = []
            if (cinema.tag?.allowRefund) tags.push({ text: '退票', type: 'blue' })
            if (cinema.tag?.endorse) tags.push({ text: '改签', type: 'blue' })
            if (cinema.tag?.snack) tags.push({ text: '小吃', type: 'orange' })
            if (cinema.tag?.vipTag) tags.push({ text: cinema.tag.vipTag, type: 'orange' })
            cinema.tag?.hallType?.forEach((h) => tags.push({ text: h, type: 'blue' }))

            return (
              <div
                key={cinema.id}
                className="py-6 border-b border-hairline-dark flex justify-between items-center group hover:bg-surface-elevated/40 hover:border-hairline-dark px-4 rounded-md transition-all"
              >
                {/* Left: Info */}
                <div className="flex-1">
                  <h3
                    onClick={() => router.push(`/cinema-detail?cinemaId=${cinema.id}`)}
                    className="text-base font-medium text-body-dark mb-1 cursor-pointer hover:text-primary transition-colors"
                  >
                    {cinema.nm}
                  </h3>
                  <div className="text-sm text-muted mb-2 truncate max-w-[600px]">
                    {cinema.addr}
                  </div>
                  <div className="flex space-x-2 flex-wrap gap-y-1">
                    {tags.slice(0, 5).map((tag, idx) => (
                      <span
                        key={idx}
                        className={`text-xs px-1.5 py-0.5 border rounded ${
                          tag.type === 'blue'
                            ? 'border-info/40 text-info'
                            : 'border-primary/40 text-primary'
                        }`}
                      >
                        {tag.text}
                      </span>
                    ))}
                  </div>
                </div>

                {/* Right: Price & Buy */}
                <div className="flex items-center space-x-6 text-right">
                  <div>
                    {cinema.distance && (
                      <div className="font-plex text-xs text-muted">
                        {cinema.distance}
                      </div>
                    )}
                  </div>
                  <button
                    onClick={() => router.push(`/cinema-detail?cinemaId=${cinema.id}`)}
                    className="px-5 py-2 bg-primary text-on-primary rounded-md text-sm font-medium shadow-sm hover:bg-primary-active transition-colors pressable"
                  >
                    选座购票
                  </button>
                </div>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
