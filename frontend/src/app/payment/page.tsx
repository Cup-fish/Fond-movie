'use client'

import { Suspense, useState, useEffect, useRef } from 'react'
import { useSearchParams, useRouter } from 'next/navigation'
import { ArrowLeft, Coins, QrCode, ScanLine } from 'lucide-react'
import { QRCodeSVG } from 'qrcode.react'
import { toast } from 'sonner'
import Loading from '@/components/Loading'
import api from '@/lib/api'
import { useUserStore } from '@/store/user'
import type { OrderItem } from '@/types'

function PaymentContent() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const orderNo = searchParams.get('orderNo')
  const { points, setPoints, fetchPoints, isLogged } = useUserStore()

  const [order, setOrder] = useState<OrderItem | null>(null)
  const [loading, setLoading] = useState(true)
  const [paying, setPaying] = useState(false)
  const [timeLeft, setTimeLeft] = useState(0)
  const [showQr, setShowQr] = useState(false)
  const [paymentNo, setPaymentNo] = useState('')
  const [qrValue, setQrValue] = useState('')
  const timerRef = useRef<NodeJS.Timeout | null>(null)
  const pollRef = useRef<NodeJS.Timeout | null>(null)

  // 加载订单详情 + 刷新积分
  useEffect(() => {
    if (!orderNo) return
    fetchPoints()
    api.getOrderDetail({ orderNo })
      .then((res) => {
        const data = res.data || res
        setOrder(data)
        if (data.expireTime) {
          const expire = new Date(data.expireTime).getTime()
          const now = Date.now()
          const diff = Math.max(0, Math.floor((expire - now) / 1000))
          setTimeLeft(diff)
        }
      })
      .catch((e) => {
        // 401（会话过期）由全局拦截器清理并跳转登录，这里不重复提示
        if (e?.response?.status !== 401) toast.error('加载订单失败')
      })
      .finally(() => setLoading(false))
  }, [orderNo, fetchPoints])

  // 倒计时
  useEffect(() => {
    if (timeLeft <= 0) return
    timerRef.current = setInterval(() => {
      setTimeLeft((prev) => {
        if (prev <= 1) {
          clearInterval(timerRef.current!)
          stopPolling()
          toast.error('订单已超时，请重新选座')
          router.push('/')
          return 0
        }
        return prev - 1
      })
    }, 1000)
    return () => { if (timerRef.current) clearInterval(timerRef.current) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [timeLeft > 0])

  // 组件卸载时清理轮询
  useEffect(() => () => stopPolling(), [])

  const formatTime = (seconds: number) => {
    const m = Math.floor(seconds / 60)
    const s = seconds % 60
    return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`
  }

  const pointsCost = order ? Math.ceil(Number(order.totalPrice)) : 0
  const hasEnoughPoints = points >= pointsCost

  // ==================== 扫码支付 ====================

  let pollingInFlight = false

  const stopPolling = () => {
    if (pollRef.current) {
      clearTimeout(pollRef.current)
      pollRef.current = null
    }
  }

  const pollOnce = async () => {
    if (pollingInFlight || !orderNo) return
    pollingInFlight = true
    try {
      const res = await api.getPaymentStatus({ orderNo })
      const data = res.data || res
      // 订单已支付 → 轮询到成功才跳转
      if (data.orderStatus === 1) {
        stopPolling()
        if (data.remainingPoints != null) setPoints(data.remainingPoints)
        toast.success('支付成功！')
        router.push(`/order-success?orderNo=${orderNo}`)
        return
      }
      if (data.paymentStatus === 2 || data.orderStatus === 2) {
        // 支付单已关闭 / 订单已取消
        stopPolling()
        toast.error('订单已关闭，请重新选座')
        router.push('/')
        return
      }
    } catch {
      // 网络抖动：下一轮继续轮询
    } finally {
      pollingInFlight = false
      // 只有未被 stopPolling 终止时才继续下一轮
      if (pollRef.current) {
        pollRef.current = setTimeout(pollOnce, 3000)
      }
    }
  }

  const startPolling = () => {
    stopPolling()
    pollRef.current = setTimeout(pollOnce, 3000)
  }

  /** 创建支付单并弹出二维码 */
  const handleGoScan = async () => {
    if (!orderNo || paying) return
    if (!hasEnoughPoints) {
      toast.error(`积分不足，需要${pointsCost}积分，当前${points}积分`)
      return
    }
    setPaying(true)
    try {
      const res = await api.createPayment({ orderNo })
      if (res.code === 200) {
        setPaymentNo(res.data?.paymentNo || '')
        // 二维码内容指向当前站点收银台（手机同 Wi-Fi 扫码可访问局域网 IP）
        setQrValue(`${window.location.origin}/mock-pay?orderNo=${orderNo}&paymentNo=${res.data?.paymentNo || ''}`)
        setShowQr(true)
        startPolling()
      } else {
        toast.error(res.message || '创建支付单失败')
      }
    } catch (e: any) {
      // 401（会话过期）由全局拦截器清理并跳转登录，这里不重复弹原始错误
      if (e?.response?.status !== 401) {
        const msg = e?.response?.data?.message || '创建支付单失败'
        toast.error(msg)
      }
    } finally {
      setPaying(false)
    }
  }

  /** 桌面演示：新开收银台窗口模拟扫码 */
  const handleMockScan = () => {
    if (!qrValue) return
    window.open(qrValue, '_blank')
  }

  if (loading) return <Loading />
  if (!order) return <div className="text-center py-20 text-muted">订单不存在</div>

  const isPending = order.status === 0

  return (
    <div className="min-h-screen bg-surface-strong">
      <div className="bg-white border-b border-hairline-light min-w-[1200px]">
        <div className="max-w-[1200px] mx-auto h-[60px] flex items-center gap-4">
          <ArrowLeft className="w-6 h-6 text-muted cursor-pointer hover:text-primary" onClick={() => router.back()} />
          <h1 className="text-lg font-medium text-ink">确认订单</h1>
        </div>
      </div>

      <div className="bg-white border-b border-hairline-light min-w-[1200px]">
        <div className="max-w-[1200px] mx-auto flex items-center justify-center py-4 gap-2 text-sm">
          {['选择场次', '选择座位', '扫码支付', '影院取票观影'].map((step, idx) => (
            <div key={step} className="flex items-center gap-2">
              <span className={`w-6 h-6 rounded-full flex items-center justify-center text-xs font-bold ${idx <= 2 ? 'bg-primary text-on-primary' : 'bg-ink text-primary'}`}>{idx + 1}</span>
              <span className={`font-medium ${idx <= 2 ? 'text-ink' : 'text-ink/70'}`}>{step}</span>
              {idx < 3 && <span className="text-ink/30 mx-2">→</span>}
            </div>
          ))}
        </div>
      </div>

      <div className="max-w-[800px] mx-auto mt-8">
        {isPending && timeLeft > 0 && (
          <div className="bg-ink border border-ink rounded-lg p-4 mb-6 flex items-center justify-between">
            <span className="text-white/90">请在规定时间内完成支付，超时订单将自动取消</span>
            <span className="font-plex text-2xl font-bold text-primary">{formatTime(timeLeft)}</span>
          </div>
        )}

        {/* 订单信息 */}
        <div className="bg-white border border-hairline-light rounded-lg shadow-card-light p-6 mb-6">
          <h2 className="text-lg font-semibold text-ink mb-4">订单信息</h2>
          <div className="grid grid-cols-2 gap-y-3 text-sm">
            <div className="text-ink/70 font-medium">订单编号</div>
            <div className="font-plex text-ink">{order.orderNo}</div>
            <div className="text-ink/70 font-medium">电影</div>
            <div className="text-ink">{order.movieName || '-'}</div>
            <div className="text-ink/70 font-medium">影院</div>
            <div className="text-ink">{order.cinemaName || '-'}</div>
            <div className="text-ink/70 font-medium">影厅</div>
            <div className="text-ink">{order.hallName || '-'}</div>
            <div className="text-ink/70 font-medium">场次</div>
            <div className="text-ink">{order.showTime || '-'}</div>
            <div className="text-ink/70 font-medium">座位</div>
            <div className="text-ink">{order.seatsInfo || `${order.seatCount}张`}</div>
            <div className="text-ink/70 font-medium">单价</div>
            <div className="text-ink">{Math.ceil(Number(order.unitPrice))} 积分</div>
          </div>
          <div className="border-t border-hairline-light mt-4 pt-4 flex items-baseline justify-between">
            <span className="font-medium text-ink">需支付</span>
            {/* 黄黑撞色金额：黑底黄字 */}
            <div className="flex items-center gap-2 bg-ink text-primary rounded-lg px-4 py-2">
              <Coins className="w-5 h-5 text-primary" />
              <span className="font-plex text-3xl font-bold">{pointsCost}</span>
              <span className="text-sm text-white/80">积分</span>
            </div>
          </div>
        </div>

        {/* 积分信息 & 支付 */}
        {isPending && (
          <>
            <div className="bg-white border border-hairline-light rounded-lg shadow-card-light p-6 mb-6">
              <h2 className="text-lg font-semibold text-ink mb-4">积分支付</h2>
              <div className="flex items-center justify-between p-4 rounded-lg border-2 border-primary bg-primary/10">
                <div className="flex items-center gap-3">
                  <div className="w-10 h-10 rounded-full bg-primary flex items-center justify-center">
                    <Coins className="w-5 h-5 text-on-primary" />
                  </div>
                  <div>
                    <div className="font-semibold text-ink">我的积分</div>
                    <div className="text-sm text-ink/70">1积分 = 1元</div>
                  </div>
                </div>
                <div className="text-right">
                  <div className="font-plex text-2xl font-bold text-ink">{points}</div>
                  <div className="text-xs text-ink/60">当前余额</div>
                </div>
              </div>
              {!hasEnoughPoints && (
                <div className="mt-3 text-sm font-medium text-trading-down bg-surface-strong border border-hairline-light p-3 rounded-md">
                  积分不足！需要 {pointsCost} 积分，当前仅有 {points} 积分
                </div>
              )}
              {hasEnoughPoints && (
                <div className="mt-3 text-sm font-medium text-trading-up bg-surface-strong border border-hairline-light p-3 rounded-md">
                  支付后剩余 {points - pointsCost} 积分
                </div>
              )}
            </div>

            <button
              onClick={handleGoScan}
              disabled={paying || !hasEnoughPoints}
              className={`w-full py-4 rounded-md text-on-primary font-semibold text-lg transition-all pressable flex items-center justify-center gap-2 ${
                paying || !hasEnoughPoints
                  ? 'bg-primary-disabled text-muted cursor-not-allowed'
                  : 'bg-primary hover:bg-primary-active'
              }`}
            >
              {paying ? (
                '创建支付单...'
              ) : (
                <>
                  <QrCode className="w-5 h-5" />
                  去扫码支付 {pointsCost} 积分
                </>
              )}
            </button>

            {/* 二维码弹层 */}
            {showQr && (
              <div className="bg-white border border-hairline-light rounded-lg shadow-card-light p-6 mb-6">
                <div className="flex items-center gap-2 mb-4">
                  <ScanLine className="w-5 h-5 text-primary" />
                  <h2 className="text-lg font-semibold text-ink">扫码支付</h2>
                  <span className="ml-auto text-xs font-medium text-ink bg-primary/15 border border-primary/40 px-2 py-0.5 rounded-full flex items-center gap-1.5">
                    <span className="w-1.5 h-1.5 rounded-full bg-primary animate-pulse-dot" />
                    等待支付...
                  </span>
                </div>
                <div className="flex flex-col items-center">
                  {/* 二维码 + 扫光动画 */}
                  <div className="relative p-4 bg-white border border-hairline-light rounded-lg overflow-hidden">
                    <QRCodeSVG value={qrValue} size={180} />
                    <div className="scan-line" style={{ top: '0%' }} />
                  </div>
                  <p className="text-sm font-medium text-ink/80 mt-3">使用手机扫码付款（需与电脑同一网络）</p>
                  {paymentNo && (
                    <p className="font-plex text-xs text-ink/70 mt-1">支付单号：{paymentNo}</p>
                  )}
                  <button
                    onClick={handleMockScan}
                    className="mt-4 px-6 py-2.5 rounded-md bg-ink text-primary border border-ink text-sm font-semibold hover:bg-surface-elevated transition-all pressable"
                  >
                    模拟扫码（桌面演示）
                  </button>
                </div>
              </div>
            )}
          </>
        )}

        {!isPending && (
          <div className="bg-white border border-hairline-light rounded-lg shadow-card-light p-8 text-center">
            <div className="text-lg text-ink">
              订单状态：<span className="font-medium text-primary">{order.statusDesc}</span>
            </div>
            <button onClick={() => router.push('/')} className="mt-4 px-6 py-2.5 bg-primary text-on-primary rounded-md font-medium hover:bg-primary-active transition-colors pressable">返回首页</button>
          </div>
        )}
      </div>
    </div>
  )
}

export default function PaymentPage() {
  return (
    <Suspense fallback={<Loading />}>
      <PaymentContent />
    </Suspense>
  )
}
