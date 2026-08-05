'use client'

import { Suspense, useState, useEffect } from 'react'
import { useSearchParams, useRouter } from 'next/navigation'
import { CheckCircle2, Ticket, Home, Coins } from 'lucide-react'
import Loading from '@/components/Loading'
import api from '@/lib/api'
import type { OrderItem } from '@/types'

function OrderSuccessContent() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const orderNo = searchParams.get('orderNo')

  const [order, setOrder] = useState<OrderItem | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    if (!orderNo) return
    api.getOrderDetail({ orderNo })
      .then((res) => setOrder(res.data || res))
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [orderNo])

  if (loading) return <Loading />

  return (
    <div className="min-h-screen bg-surface-strong flex flex-col items-center pt-20">
      {/* 成功图标 */}
      <div className="w-20 h-20 rounded-full bg-trading-up/10 border border-trading-up/30 flex items-center justify-center mb-6">
        <CheckCircle2 className="w-12 h-12 text-trading-up" />
      </div>

      <h1 className="text-2xl font-bold tracking-tight text-ink mb-2">支付成功！</h1>
      <p className="text-muted mb-8">请凭订单信息到影院取票观影</p>

      {/* 订单卡片 — 深色票面 + 浅色详情 */}
      {order && (
        <div className="bg-white border border-hairline-light rounded-lg shadow-card-light w-[480px] overflow-hidden">
          {/* 影片信息 — 深色票面带（ink 底 + 黄色强调） */}
          <div className="bg-ink text-white p-6 relative overflow-hidden">
            <div className="absolute -right-8 -top-8 w-32 h-32 rounded-full bg-primary/10" />
            <div className="text-xl font-bold mb-1">{order.movieName}</div>
            <div className="text-sm text-white/70">{order.cinemaName} · {order.hallName}</div>
          </div>

          {/* 详情 */}
          <div className="p-6 space-y-4 text-sm">
            <div className="flex justify-between">
              <span className="text-muted">场次时间</span>
              <span className="font-plex text-ink">{order.showTime}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-muted">座位信息</span>
              <span className="text-ink">{order.seatsInfo || `${order.seatCount}张`}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-muted">订单编号</span>
              <span className="font-plex text-ink text-xs">{order.orderNo}</span>
            </div>
            <div className="border-t border-hairline-light pt-4 flex justify-between items-baseline">
              <span className="text-muted">支付积分</span>
              <div className="flex items-center gap-1">
                <Coins className="w-4 h-4 text-primary" />
                <span className="font-plex text-2xl font-bold text-ink">{Math.ceil(Number(order.totalPrice))}</span>
                <span className="text-sm text-muted">积分</span>
              </div>
            </div>
          </div>

          {/* 取票码（模拟） */}
          <div className="border-t border-dashed border-hairline-light mx-6" />
          <div className="p-6 text-center">
            <div className="text-xs text-muted mb-2">取票验证码</div>
            <div className="font-plex text-3xl font-bold tracking-[0.3em] text-ink">
              {String(Math.floor(Math.random() * 900000) + 100000)}
            </div>
            <div className="text-xs text-muted mt-1">请到影院自助取票机输入此验证码取票</div>
          </div>
        </div>
      )}

      {/* 操作按钮 */}
      <div className="flex gap-4 mt-8">
        <button
          onClick={() => router.push('/orders')}
          className="flex items-center gap-2 px-6 py-3 border border-hairline-light rounded-md text-ink bg-white hover:bg-surface-strong transition-colors pressable"
        >
          <Ticket className="w-4 h-4" />
          查看我的订单
        </button>
        <button
          onClick={() => router.push('/')}
          className="flex items-center gap-2 px-6 py-3 bg-primary text-on-primary rounded-md font-medium hover:bg-primary-active transition-colors pressable"
        >
          <Home className="w-4 h-4" />
          返回首页
        </button>
      </div>
    </div>
  )
}

export default function OrderSuccessPage() {
  return (
    <Suspense fallback={<Loading />}>
      <OrderSuccessContent />
    </Suspense>
  )
}
