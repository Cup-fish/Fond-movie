'use client'

import { Suspense, useState, useEffect } from 'react'
import { useSearchParams, useRouter } from 'next/navigation'
import { ArrowLeft, MapPin } from 'lucide-react'
import Loading from '@/components/Loading'
import TopIntro from '@/components/movie-detail/TopIntro'
import StagePhoto from '@/components/movie-detail/StagePhoto'
import api from '@/lib/api'
import type { CinemaScheduleGroup, ScheduleItem } from '@/types'

function MovieDetailContent() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const movieId = searchParams.get('movieId')
  const [isReady, setIsReady] = useState(false)
  const [movieDetail, setMovieDetail] = useState<any>(null)
  const [showSchedule, setShowSchedule] = useState(false)
  const [dates, setDates] = useState<string[]>([])
  const [activeDate, setActiveDate] = useState('')
  const [cinemaGroups, setCinemaGroups] = useState<CinemaScheduleGroup[]>([])
  const [scheduleLoading, setScheduleLoading] = useState(false)

  useEffect(() => {
    if (!movieId) return
    api
      .getDetailMovie({ movieId })
      .then((res) => {
        setMovieDetail(res.detailMovie)
        setIsReady(true)
      })
      .catch(() => setIsReady(true))
  }, [movieId])

  const loadDates = () => {
    if (!movieId) return
    api.getAvailableDates({ movieId: Number(movieId) })
      .then((res) => {
        const dateList = res.data || res || []
        setDates(dateList)
        if (dateList.length > 0) {
          setActiveDate(dateList[0])
          loadSchedules(dateList[0])
        }
      })
      .catch(() => {})
  }

  const loadSchedules = (date: string) => {
    if (!movieId) return
    setScheduleLoading(true)
    api.getSchedulesByCinema({ movieId: Number(movieId), showDate: date })
      .then((res) => {
        setCinemaGroups(res.data || [])
      })
      .catch(() => {})
      .finally(() => setScheduleLoading(false))
  }

  const handleShowSchedule = () => {
    if (!showSchedule) {
      setShowSchedule(true)
      loadDates()
    } else {
      setShowSchedule(false)
    }
  }

  const handleDateSelect = (date: string) => {
    setActiveDate(date)
    loadSchedules(date)
  }

  const handleSelectSchedule = (schedule: ScheduleItem) => {
    router.push(`/seat-selection?scheduleId=${schedule.id}&movieId=${movieId}`)
  }

  const formatDateLabel = (dateStr: string) => {
    const d = new Date(dateStr)
    const today = new Date()
    const tomorrow = new Date()
    tomorrow.setDate(today.getDate() + 1)
    const month = d.getMonth() + 1
    const day = d.getDate()
    const weekdays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
    if (dateStr === today.toISOString().slice(0, 10)) return `今天 ${month}月${day}日`
    if (dateStr === tomorrow.toISOString().slice(0, 10)) return `明天 ${month}月${day}日`
    return `${weekdays[d.getDay()]} ${month}月${day}日`
  }

  if (!isReady) return <Loading />
  if (!movieDetail) return <div className="text-center py-20 text-muted">未找到电影信息</div>

  return (
    <div className="min-h-screen bg-canvas-dark">
      <div className="bg-surface-card border-b border-hairline-dark min-w-[1200px]">
        <div className="max-w-[1280px] mx-auto h-[60px] flex items-center gap-4 px-4">
          <ArrowLeft className="w-6 h-6 text-muted cursor-pointer hover:text-primary transition-colors" onClick={() => router.push('/')} />
          <h1 className="text-lg font-medium text-body-dark">{movieDetail.nm}</h1>
        </div>
      </div>

      <div className="max-w-[1280px] mx-auto mt-6 bg-surface-card border border-hairline-dark rounded-xl overflow-hidden shadow-card-dark">
        <TopIntro movieDetail={movieDetail} />
        {movieDetail.photos && <StagePhoto photos={movieDetail.photos} photoTotal={movieDetail.pn || 0} />}

        <div className="px-8 py-6 border-t border-hairline-dark">
          <button onClick={handleShowSchedule} className="px-8 py-3 bg-primary text-on-primary rounded-md text-base font-semibold hover:bg-primary-active transition-colors pressable">
            {showSchedule ? '收起排片' : '特惠购票'}
          </button>
        </div>

        {showSchedule && (
          <div className="px-8 pb-8">
            {dates.length > 0 && (
              <div className="flex gap-3 mb-6 border-b border-hairline-dark pb-4">
                {dates.map((d) => (
                  <button key={d} onClick={() => handleDateSelect(d)} className={`px-4 py-2 rounded-md text-sm transition-all pressable ${d === activeDate ? 'bg-primary text-on-primary font-medium' : 'bg-surface-elevated text-muted-strong hover:text-body-dark hover:border-primary/40 border border-hairline-dark'}`}>
                    {formatDateLabel(d)}
                  </button>
                ))}
              </div>
            )}

            {scheduleLoading ? (
              <Loading />
            ) : cinemaGroups.length === 0 ? (
              <div className="text-center py-12 text-muted">当日暂无排片</div>
            ) : (
              <div className="space-y-6">
                {cinemaGroups.map((group) => (
                  <div key={group.cinemaId} className="border border-hairline-dark rounded-lg overflow-hidden">
                    <div className="bg-surface-elevated/50 px-6 py-4 border-b border-hairline-dark">
                      <h3 className="font-medium text-body-dark text-base">{group.cinemaName}</h3>
                      {group.cinemaAddr && (
                        <div className="flex items-center text-sm text-muted mt-1">
                          <MapPin className="w-3.5 h-3.5 mr-1" />
                          <span>{group.cinemaAddr}</span>
                        </div>
                      )}
                    </div>
                    <div className="divide-y divide-hairline-dark">
                      {group.schedules.map((schedule) => (
                        <div key={schedule.id} className="flex items-center justify-between px-6 py-4 hover:bg-surface-elevated/40 transition-colors">
                          <div className="flex items-center gap-8">
                            <div className="text-center">
                              <div className="text-lg font-bold text-body-dark">{schedule.showTime}</div>
                              <div className="font-plex text-xs text-muted">{schedule.endTime}散场</div>
                            </div>
                            <div className="text-sm text-muted-strong">
                              <div className="flex items-center gap-2">
                                <span>{schedule.lang}</span>
                                <span className="text-muted">|</span>
                                <span>{schedule.hallName}</span>
                              </div>
                            </div>
                          </div>
                          <div className="flex items-center gap-6">
                            <div className="text-right">
                              <span className="font-plex text-xs text-primary">¥</span>
                              <span className="font-plex text-xl font-bold text-primary">{schedule.price}</span>
                            </div>
                            <button onClick={() => handleSelectSchedule(schedule)} className="px-6 py-2 bg-primary text-on-primary rounded-md text-sm font-medium hover:bg-primary-active transition-colors pressable">
                              选座购票
                            </button>
                          </div>
                        </div>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  )
}

export default function MovieDetailPage() {
  return (
    <Suspense fallback={<Loading />}>
      <MovieDetailContent />
    </Suspense>
  )
}
