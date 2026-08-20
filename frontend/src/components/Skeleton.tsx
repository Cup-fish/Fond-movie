/**
 * 骨架屏占位 — 用于列表/卡片加载，减少布局跳动，提升感知性能。
 */
export function Skeleton({ className = '' }: { className?: string }) {
  return <div className={`skeleton rounded-md ${className}`} />
}

/**
 * 电影卡片骨架屏
 */
export function MovieCardSkeleton() {
  return (
    <div className="w-[160px] flex flex-col">
      <Skeleton className="h-[220px] w-full" />
      <Skeleton className="mt-3 h-4 w-3/4" />
      <Skeleton className="mt-2 h-3 w-1/2" />
    </div>
  )
}

/**
 * 影院列表行骨架屏
 */
export function CinemaRowSkeleton() {
  return (
    <div className="py-6 border-b border-hairline-dark flex justify-between items-center px-4">
      <div className="flex-1 space-y-3">
        <Skeleton className="h-5 w-1/3" />
        <Skeleton className="h-4 w-1/2" />
        <div className="flex gap-2">
          <Skeleton className="h-5 w-16" />
          <Skeleton className="h-5 w-16" />
          <Skeleton className="h-5 w-16" />
        </div>
      </div>
      <Skeleton className="h-9 w-24" />
    </div>
  )
}
