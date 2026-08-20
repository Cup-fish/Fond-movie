'use client'

import { useState, useEffect } from 'react'
import { useRouter } from 'next/navigation'
import { toast } from 'sonner'
import { useUserStore } from '@/store/user'

/**
 * 登录页 — 交易面（浅色模式）
 * 深色主站与浅色交易面共享黄色 CTA 与灰蓝描边（DESIGN.md multi-theme）
 *
 * 支持 ?reason=expired（登录过期被跳转回来时提示）与 ?redirect=xxx（登录后回跳）
 */
export default function LoginPage() {
  const router = useRouter()
  const { loginAsync } = useUserStore()
  const [account, setAccount] = useState('')
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(false)

  // 登录过期被跳转回登录页 → 明确提示原因
  useEffect(() => {
    try {
      const params = new URLSearchParams(window.location.search)
      if (params.get('reason') === 'expired') {
        toast.error('登录已过期，请重新登录')
      }
    } catch {}
  }, [])

  const handleLogin = async () => {
    if (!account.trim()) return toast.error('用户名不能为空！')
    if (!password) return toast.error('请输入密码！')

    setLoading(true)
    try {
      const result = await loginAsync(account, password)
      if (result.success) {
        toast.success('登录成功')
        // 从 ?redirect= 取回跳地址（登录过期/未登录被拦截时），默认回首页
        let redirectTo = '/'
        try {
          const params = new URLSearchParams(window.location.search)
          const r = params.get('redirect')
          if (r && r.startsWith('/') && !r.startsWith('//')) redirectTo = r
        } catch {}
        router.push(redirectTo)
      } else {
        toast.error(result.msg || '登录失败')
      }
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-surface-strong flex items-center justify-center">
      <div className="w-[400px] bg-white border border-hairline-light rounded-lg shadow-card-light p-8">
        <div className="flex items-center justify-center mb-8">
          <div className="w-10 h-10 bg-primary rounded-full flex items-center justify-center mr-2">
            <span className="text-on-primary font-bold text-xl">猫</span>
          </div>
          <span className="text-2xl font-bold tracking-tight text-ink">猫眼电影</span>
        </div>
        <h2 className="text-xl font-medium text-center mb-6 text-ink">登 录</h2>
        <div className="space-y-4">
          <input
            type="text"
            placeholder="用户名"
            value={account}
            onChange={(e) => setAccount(e.target.value)}
            className="w-full py-3 px-4 border border-hairline-light rounded-md outline-none text-base text-ink placeholder:text-muted focus:border-primary transition-colors"
          />
          <input
            type="password"
            placeholder="密码"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && handleLogin()}
            className="w-full py-3 px-4 border border-hairline-light rounded-md outline-none text-base text-ink placeholder:text-muted focus:border-primary transition-colors"
          />
          <button
            onClick={handleLogin}
            disabled={loading}
            className="w-full py-3 bg-primary text-on-primary rounded-md text-base font-semibold disabled:opacity-60 hover:bg-primary-active transition-colors pressable"
          >
            {loading ? '登录中...' : '登 录'}
          </button>
          <p className="text-sm text-muted text-center">
            还没有账号？
            <span
              className="text-primary cursor-pointer hover:underline ml-1 font-medium"
              onClick={() => router.push('/register')}
            >
              去注册
            </span>
          </p>
          <p className="text-sm text-muted-strong text-center">
            <span className="cursor-pointer hover:text-primary transition-colors" onClick={() => router.push('/')}>
              返回首页
            </span>
          </p>
        </div>
      </div>
    </div>
  )
}
