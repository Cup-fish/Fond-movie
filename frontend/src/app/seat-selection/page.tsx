'use client'

import { Suspense, useState, useEffect, useCallback } from 'react'
import { useSearchParams, useRouter } from 'next/navigation'
import { motion, useReducedMotion } from 'motion/react'
import { ArrowLeft, Monitor, Coins, X } from 'lucide-react'
import { toast } from 'sonner'
import Loading from '@/components/Loading'
import QueueWaiting from '@/components/QueueWaiting'
import api from '@/lib/api'
import { imgUrlReplace } from '@/lib/utils'
import { useUserStore } from '@/store/user'
import type { SeatInfo, SeatLayoutData } from '@/types'

function SeatSelectionContent() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const scheduleId = searchParams.get('scheduleId')
  const movieId = searchParams.get('movieId')
  const { isLogged } = useUserStore()
  const reduce = useReducedMotion()

  const [layout, setLayout] = useState<SeatLayoutData | null>(null)
  const [movieDetail, setMovieDetail] = useState<any>(null)
  const [schedule, setSchedule] = useState<any>(null)
  const [selectedSeats, setSelectedSeats] = useState<SeatInfo[]>([])
  const [loading, setLoading] = useState(true)
  const [locking, setLocking] = useState(false)

  // 排队状态
  const [queueAdmitted, setQueueAdmitted] = useState(false)
  const [queuePosition, setQueuePosition] = useState(0)
  const [queueWait, setQueueWait] = useState(0)
  const [queueChecked, setQueueChecked] = useState(false)

  const MAX_SEATS = 6

  // 第一步：检查排队准入
  useEffect(() => {
    if (!scheduleId) return

    if (isLogged) {
      api
        .queueEnter({ scheduleId: Number(scheduleId) })
        .then((res) => {
          const data = res.data || res
          if (data.admitted) {
            setQueueAdmitted(true)
          } else {
            setQueuePosition(data.position)
            setQueueWait(data.estimatedWaitSeconds)
          }
        })
        .catch(() => {
          // 排队接口失败 → 直接放行（降级安全）
          setQueueAdmitted(true)
        })
        .finally(() => setQueueChecked(true))
    } else {
      // 未登录 → 跳过排队，展示座位图（但锁座时会提示登录）
      setQueueAdmitted(true)
      setQueueChecked(true)
    }
  }, [scheduleId, isLogged])

  // 第二步：排队通过后加载数据
  useEffect(() => {
    if (!queueAdmitted || !scheduleId || !queueChecked) return

    const loadData = async () => {
      try {
        const [layoutRes, movieRes] = await Promise.all([
          api.getSeatLayout({ scheduleId: Number(scheduleId) }),
          movieId ? api.getDetailMovie({ movieId }) : null,
        ])

        setLayout(layoutRes.data || layoutRes)
        if (movieRes) setMovieDetail(movieRes.detailMovie)

        if (movieId) {
          const scheduleRes = await api.getSchedules({ movieId: Number(movieId) })
          const schedules = scheduleRes.data || []
          const found = schedules.find((s: any) => s.id === Number(scheduleId))
          if (found) setSchedule(found)
        }
      } catch (e) {
        toast.error('加载座位信息失败')
      } finally {
        setLoading(false)
      }
    }

    loadData()
  }, [queueAdmitted, scheduleId, movieId, queueChecked])

  const handleAdmitted = useCallback(() => {
    setQueueAdmitted(true)
    setLoading(true)
  }, [])

  const handleQueueLeave = useCallback(() => {
    router.back()
  }, [router])

  // 排队中
  if (!loading && queueChecked && !queueAdmitted && scheduleId && isLogged) {
    return (
      <QueueWaiting
        scheduleId={Number(scheduleId)}
        position={queuePosition}
        estimatedWaitSeconds={queueWait}
        onAdmitted={handleAdmitted}
        onLeave={handleQueueLeave}
      />
    )
  }

  // 点击座位
  const handleSeatClick = (seat: SeatInfo) => {
    if (seat.status === -1 || seat.status === 1 || seat.status === 2) return

    const isSelected = selectedSeats.some(s => s.row === seat.row && s.col === seat.col)
    if (isSelected) {
      setSelectedSeats(prev => prev.filter(s => !(s.row === seat.row && s.col === seat.col)))
    } else {
      if (selectedSeats.length >= MAX_SEATS) {
        toast.warning(`最多选择${MAX_SEATS}个座位`)
        return
      }
      setSelectedSeats(prev => [...prev, seat])
    }
  }

  // 确认选座
  const handleConfirm = async () => {
    if (!isLogged) {
      toast.error('请先登录')
      router.push('/login')
      return
    }
    if (selectedSeats.length === 0) {
      toast.warning('请选择座位')
      return
    }

    setLocking(true)
    try {
      // 锁座 + 建单（一个请求完成，直接返回 orderNo）
      const lockRes = await api.lockSeats({
        scheduleId: Number(scheduleId),
        seats: selectedSeats.map(s => ({ row: s.row, col: s.col })),
      })

      if (lockRes.code !== 200) {
        toast.error(lockRes.message || '锁座失败')
        setLocking(false)
        return
      }

      const orderNo = lockRes.data?.orderNo
      if (!orderNo) {
        toast.error('订单创建失败')
        setLocking(false)
        return
      }

      toast.success('锁座成功，请在15分钟内完成支付')

      // 直接跳转支付页
      router.push(`/payment?orderNo=${orderNo}`)
    } catch (e: any) {
      // 401（会话过期）由全局拦截器清理并跳转登录，这里不重复弹原始错误
      if (e?.response?.status !== 401) {
        const msg = e?.response?.data?.message || '操作失败，请重试'
        toast.error(msg)
      }
    } finally {
      setLocking(false)
    }
  }

  // 交易语义色：可选=绿（可买）· 已售=灰 · 他人锁定=红（不可买）· 已选=黄 · 情侣座=蓝
  const getSeatClasses = (seat: SeatInfo, isSelected: boolean) => {
    if (seat.status === -1) return 'bg-transparent'
    if (seat.status === 1) return 'bg-surface-elevated text-muted cursor-not-allowed'
    if (seat.status === 2) return 'bg-trading-down/25 border border-trading-down/40 text-trading-down cursor-not-allowed'
    if (seat.status === 3 || isSelected) return 'bg-primary text-on-primary border-primary'
    if (seat.couple) return 'bg-info/15 border border-info/40 text-info hover:bg-info/25 cursor-pointer'
    return 'bg-trading-up/15 border border-trading-up/40 text-trading-up hover:bg-trading-up/25 cursor-pointer'
  }

  const totalPoints = schedule ? Math.ceil(selectedSeats.length * (schedule.price || 0)) : 0

  if (loading) return <Loading />

  return (
    <div className="min-h-screen bg-canvas-dark">
      {/* Top bar */}
      <div className="bg-surface-card border-b border-hairline-dark min-w-[1200px]">
        <div className="max-w-[1280px] mx-auto h-[60px] flex items-center gap-4 px-4">
          <ArrowLeft className="w-6 h-6 text-muted cursor-pointer hover:text-primary transition-colors" onClick={() => router.back()} />
          <h1 className="text-lg font-medium text-body-dark">选择座位</h1>
        </div>
      </div>

      {/* 步骤指示 */}
      <div className="bg-surface-card border-b border-hairline-dark min-w-[1200px]">
        <div className="max-w-[1280px] mx-auto flex items-center justify-center py-4 gap-2 text-sm">
          {['选择场次', '选择座位', '扫码支付', '影院取票观影'].map((step, idx) => (
            <div key={step} className="flex items-center gap-2">
              <span className={`w-6 h-6 rounded-full flex items-center justify-center text-xs font-medium ${idx <= 1 ? 'bg-primary text-on-primary' : 'bg-surface-elevated text-muted'}`}>
                {idx + 1}
              </span>
              <span className={idx <= 1 ? 'text-primary font-medium' : 'text-muted'}>{step}</span>
              {idx < 3 && <span className="text-muted mx-2">→</span>}
            </div>
          ))}
        </div>
      </div>

      <div className="max-w-[1280px] mx-auto mt-6 flex gap-6 px-4">
        {/* 左侧：座位图 */}
        <div className="flex-1 bg-surface-card border border-hairline-dark rounded-xl p-6 shadow-card-dark">
          {layout ? (
            <>
              {/* 影厅信息 */}
              <div className="text-center mb-6">
                <h2 className="text-lg font-medium text-body-dark">{layout.hallName}</h2>
                <span className="font-plex text-sm text-muted">{layout.hallType}</span>
              </div>

              {/* 银幕 */}
              <div className="flex justify-center mb-8">
                <div className="w-[60%] h-[3px] bg-gradient-to-r from-transparent via-primary/60 to-transparent rounded-full relative">
                  <div className="absolute -top-6 left-1/2 -translate-x-1/2 text-xs text-muted flex items-center gap-1">
                    <Monitor className="w-3.5 h-3.5" />
                    银幕
                  </div>
                </div>
              </div>

              {/* 座位图例 */}
              <div className="flex items-center justify-center gap-6 mb-4 text-xs text-muted-strong">
                <div className="flex items-center gap-1.5">
                  <div className="w-4 h-4 bg-trading-up/15 border border-trading-up/40 rounded" />
                  <span>可选</span>
                </div>
                <div className="flex items-center gap-1.5">
                  <div className="w-4 h-4 bg-primary rounded" />
                  <span>已选</span>
                </div>
                <div className="flex items-center gap-1.5">
                  <div className="w-4 h-4 bg-surface-elevated border border-hairline-dark rounded" />
                  <span>已售</span>
                </div>
                <div className="flex items-center gap-1.5">
                  <div className="w-4 h-4 bg-trading-down/25 border border-trading-down/40 rounded" />
                  <span>锁定</span>
                </div>
                <div className="flex items-center gap-1.5">
                  <div className="w-4 h-4 bg-info/15 border border-info/40 rounded" />
                  <span>情侣座</span>
                </div>
              </div>

              {/* 座位网格 */}
              <div className="flex justify-center overflow-x-auto">
                <div className="inline-block">
                  {layout.seats.map((row, rowIdx) => (
                    <div key={rowIdx} className="flex items-center mb-1">
                      {/* 行号 */}
                      <div className="font-plex w-6 text-xs text-muted text-right mr-2 shrink-0">
                        {rowIdx + 1}
                      </div>
                      {row.map((seat, colIdx) => {
                        const isSelected = selectedSeats.some(s => s.row === seat.row && s.col === seat.col)
                        const hasAisle = layout.aisles.includes(colIdx + 1)
                        return (
                          <div key={colIdx} className={`flex items-center ${hasAisle ? 'mr-4' : ''}`}>
                            {seat.status === -1 ? (
                              <div className="w-7 h-7 m-0.5" />
                            ) : (
                              <motion.div
                                onClick={() => handleSeatClick(seat)}
                                title={seat.label}
                                initial={reduce ? false : { scale: 0.6, opacity: 0 }}
                                animate={{ scale: 1, opacity: 1 }}
                                whileTap={reduce ? undefined : { scale: 0.85 }}
                                transition={{ type: 'spring', stiffness: 500, damping: 30 }}
                                className={`w-7 h-7 m-0.5 rounded border text-[10px] font-plex flex items-center justify-center transition-colors duration-150 ${getSeatClasses(seat, isSelected)} ${isSelected ? 'scale-105 font-bold' : ''}`}
                              >
                                {isSelected ? '✓' : seat.col}
                              </motion.div>
                            )}
                          </div>
                        )
                      })}
                    </div>
                  ))}
                </div>
              </div>
            </>
          ) : (
            <div className="text-center py-12 text-muted">暂无座位信息</div>
          )}
        </div>

        {/* 右侧：选座信息 */}
        <div className="w-[320px] shrink-0">
          <div className="bg-surface-card border border-hairline-dark rounded-xl p-6 sticky top-[100px] shadow-card-dark">
            {/* 电影信息 */}
            {movieDetail && (
              <div className="flex gap-3 mb-6 pb-4 border-b border-hairline-dark">
                <img src={imgUrlReplace(movieDetail.img)} alt={movieDetail.nm} className="w-16 h-22 rounded object-cover" />
                <div className="flex-1 min-w-0">
                  <h3 className="font-medium text-body-dark truncate">{movieDetail.nm}</h3>
                  {movieDetail.cat && <p className="text-xs text-muted mt-1">{movieDetail.cat}</p>}
                  {movieDetail.dur && <p className="font-plex text-xs text-muted">{movieDetail.dur}分钟</p>}
                </div>
              </div>
            )}

            {/* 场次信息 */}
            {schedule && (
              <div className="mb-6 pb-4 border-b border-hairline-dark text-sm text-muted-strong">
                <p>{schedule.showDate} {schedule.showTime} - {schedule.endTime}</p>
                <p className="text-muted mt-1">{schedule.hallName} / {schedule.lang}</p>
              </div>
            )}

            {/* 已选座位 */}
            <div className="mb-6">
              <h4 className="text-sm font-medium text-body-dark mb-3">
                已选座位 (<span className="font-plex text-primary">{selectedSeats.length}</span>/{MAX_SEATS})
              </h4>
              {selectedSeats.length === 0 ? (
                <p className="text-sm text-muted">请在左侧选择座位</p>
              ) : (
                <div className="space-y-2">
                  {selectedSeats.map((seat) => (
                    <motion.div
                      key={`${seat.row}-${seat.col}`}
                      initial={reduce ? false : { opacity: 0, x: -8 }}
                      animate={{ opacity: 1, x: 0 }}
                      className="flex items-center justify-between text-sm"
                    >
                      <span className="text-muted-strong">{seat.label}</span>
                      <div className="flex items-center gap-2">
                        <span className="font-plex text-primary font-medium">{Math.ceil(schedule?.price || 0)} 积分</span>
                        <button
                          onClick={() => setSelectedSeats(prev => prev.filter(s => !(s.row === seat.row && s.col === seat.col)))}
                          className="text-muted hover:text-trading-down transition-colors"
                        >
                          <X size={14} />
                        </button>
                      </div>
                    </motion.div>
                  ))}
                </div>
              )}
            </div>

            {/* 总价 + 确认 */}
            <div className="border-t border-hairline-dark pt-4">
              <div className="flex items-baseline justify-between mb-4">
                <span className="text-muted-strong text-sm">总计</span>
                <div className="flex items-center gap-1">
                  <Coins className="w-5 h-5 text-primary" />
                  <span className="font-plex text-2xl font-bold text-primary">{totalPoints}</span>
                  <span className="text-sm text-primary">积分</span>
                </div>
              </div>
              <button
                onClick={handleConfirm}
                disabled={selectedSeats.length === 0 || locking}
                className={`w-full py-3.5 rounded-md font-semibold transition-all pressable ${
                  selectedSeats.length === 0 || locking
                    ? 'bg-primary-disabled text-muted cursor-not-allowed'
                    : 'bg-primary text-on-primary hover:bg-primary-active'
                }`}
              >
                {locking ? '锁座中...' : `确认选座（${selectedSeats.length}张）`}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

export default function SeatSelectionPage() {
  return (
    <Suspense fallback={<Loading />}>
      <SeatSelectionContent />
    </Suspense>
  )
}
