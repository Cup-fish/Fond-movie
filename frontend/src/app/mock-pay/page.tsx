'use client'

import { Suspense, useEffect, useState } from 'react'
import { useSearchParams } from 'next/navigation'
import { toast } from 'sonner'
import { CheckCircle2, Coins, Loader2 } from 'lucide-react'
import Loading from '@/components/Loading'
import api from '@/lib/api'
import type { MockOrderInfoItem } from '@/types'

/**
 * 收银台页面（模拟支付网关侧，无用户态）
 *
 * 支付页二维码扫码/「模拟扫码」打开本页 → 展示订单摘要 → 确认付款
 * → 调模拟网关回调 mock/notify → 支付页轮询检测到已支付
 */
function MockPayContent() {
  const searchParams = useSearchParams()
  const orderNo = searchParams.get('orderNo')

  const [info, setInfo] = useState<MockOrderInfoItem | null>(null)
  const [loading, setLoading] = useState(true)
  const [paying, setPaying] = useState(false)
  const [done, setDone] = useState(false)
  const [timeLeft, setTimeLeft] = useState(0)

  useEffect(() => {
    if (!orderNo) {
      setLoading(false)
      return
    }
    api
      .getMockOrderInfo({ orderNo })
      .then((res) => {
        const data = res.data || res
        if (data?.orderNo) {
          setInfo(data)
        } else {
          toast.error(res.message || '订单不存在')
        }
      })
      .catch(() => toast.error('加载订单失败'))
      .finally(() => setLoading(false))
  }, [orderNo])

  // 支付倒计时（订单过期时间）
  useEffect(() => {
    if (!info?.expireTime || done) return
    const expire = new Date(info.expireTime).getTime()
    const tick = () => {
      const diff = Math.max(0, Math.floor((expire - Date.now()) / 1000))
      setTimeLeft(diff)
      if (diff <= 0) clearInterval(timer)
    }
    const timer = setInterval(tick, 1000)
    tick()
    return () => clearInterval(timer)
  }, [info?.expireTime, done])

  const formatTime = (seconds: number) => {
    const m = Math.floor(seconds / 60)
    const s = seconds % 60
    return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`
  }

  const handleConfirm = async () => {
    if (!orderNo || paying || done) return
    setPaying(true)
    try {
      // 模拟支付网关处理中（真实场景为支付宝/微信交互 + 异步回调）
      await new Promise((r) => setTimeout(r, 1200))
      const res = await api.mockNotify({ orderNo })
      if (res.code === 200) {
        setDone(true)
        toast.success('支付成功！')
      } else {
        toast.error(res.message || '支付失败')
      }
    } catch (e: any) {
      toast.error(e?.response?.data?.message || '支付失败，请重试')
    } finally {
      setPaying(false)
    }
  }

  if (loading) return <Loading />
  if (!orderNo) return <div className="min-h-screen bg-surface-strong flex items-center justify-center text-muted">缺少订单参数</div>
  if (!info) return <div className="min-h-screen bg-surface-strong flex items-center justify-center text-muted">订单不存在或已失效</div>

  const isPaid = info.orderStatus === 1

  return (
    <div className="min-h-screen bg-surface-strong flex items-center justify-center p-4">
      <div className="w-[420px] bg-white border border-hairline-light rounded-xl shadow-card-light overflow-hidden">
        {/* 头部 — 交易面深色品牌带（trading 色仅作文字信号，不作背景） */}
        <div className="py-8 text-center bg-ink">
          <div className="w-16 h-16 mx-auto rounded-full bg-white flex items-center justify-center mb-3">
            <Coins className={`w-8 h-8 ${done || isPaid ? 'text-trading-up' : 'text-primary'}`} />
          </div>
          <div className="text-white font-medium">{done || isPaid ? '支付成功' : '收银台'}</div>
          <div className="text-white/70 text-xs mt-1">模拟支付网关 · 仅用于演示</div>
        </div>

        <div className="p-6">
          {done || isPaid ? (
            <div className="text-center py-6">
              <CheckCircle2 className="w-14 h-14 text-trading-up mx-auto mb-3" />
              <div className="text-lg font-medium text-ink">付款成功</div>
              <div className="text-sm text-muted mt-2">订单已支付，请返回支付页面查看结果</div>
            </div>
          ) : (
            <>
              {/* 订单摘要 */}
              <div className="space-y-3 text-sm">
                <div className="flex justify-between">
                  <span className="font-medium text-ink/70">订单编号</span>
                  <span className="font-plex text-ink text-xs">{info.orderNo}</span>
                </div>
                <div className="flex justify-between">
                  <span className="font-medium text-ink/70">电影</span>
                  <span className="font-medium text-ink">{info.movieName}</span>
                </div>
                <div className="flex justify-between">
                  <span className="font-medium text-ink/70">影院</span>
                  <span className="text-ink">{info.cinemaName}</span>
                </div>
                <div className="flex justify-between">
                  <span className="font-medium text-ink/70">影厅/场次</span>
                  <span className="text-ink">{info.hallName} · {info.showTime}</span>
                </div>
                <div className="flex justify-between">
                  <span className="font-medium text-ink/70">座位</span>
                  <span className="text-ink">{info.seatsInfo}</span>
                </div>
              </div>

              <div className="border-t border-hairline-light my-4 pt-4 flex items-baseline justify-between">
                <span className="text-ink">应付金额</span>
                <div className="flex items-center gap-1">
                  <Coins className="w-5 h-5 text-primary" />
                  <span className="font-plex text-3xl font-bold text-ink">{Math.ceil(Number(info.totalPrice))}</span>
                  <span className="text-sm text-muted">积分</span>
                </div>
              </div>

              {/* 支付倒计时 */}
              {timeLeft > 0 && (
                <div className="flex items-center justify-center gap-2 mb-3 text-sm">
                  <span className="text-muted">支付剩余时间</span>
                  <span className={`font-plex font-bold ${timeLeft <= 60 ? 'text-trading-down' : 'text-ink'}`}>
                    {formatTime(timeLeft)}
                  </span>
                </div>
              )}
              {timeLeft === 0 && !done && (
                <div className="text-center mb-3 text-sm font-medium text-trading-down">
                  订单已过期，请返回重新选座
                </div>
              )}

              <button
                onClick={handleConfirm}
                disabled={paying || timeLeft === 0}
                className="w-full py-3.5 rounded-md bg-primary text-on-primary font-semibold text-base disabled:opacity-50 hover:bg-primary-active transition-colors pressable flex items-center justify-center gap-2"
              >
                {paying ? (
                  <>
                    <Loader2 className="w-5 h-5 animate-spin" />
                    支付处理中...
                  </>
                ) : (
                  '确认付款'
                )}
              </button>
              <p className="text-xs text-muted text-center mt-3">确认后将立即发起扣款（演示环境）</p>
            </>
          )}
        </div>
      </div>
    </div>
  )
}

export default function MockPayPage() {
  return (
    <Suspense fallback={<Loading />}>
      <MockPayContent />
    </Suspense>
  )
}
