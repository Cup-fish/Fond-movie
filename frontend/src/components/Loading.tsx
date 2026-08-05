/**
 * 加载态 — 品牌黄脉冲 + 等宽文字（替代通用 spinner）
 */
export default function Loading({ label = '加载中' }: { label?: string }) {
  return (
    <div className="flex flex-col items-center justify-center py-24">
      <div className="relative w-12 h-12">
        <div className="absolute inset-0 rounded-full border-2 border-surface-elevated" />
        <div className="absolute inset-0 rounded-full border-2 border-transparent border-t-primary animate-spin" />
        <div className="absolute inset-0 flex items-center justify-center">
          <span className="w-2.5 h-2.5 rounded-full bg-primary animate-pulse-dot" />
        </div>
      </div>
      <p className="text-sm text-muted mt-4 font-plex tracking-wide">{label}...</p>
    </div>
  )
}
