'use client'

import { ChevronRight } from 'lucide-react'
import { imgUrlReplace } from '@/lib/utils'

interface Props {
  photos: string[]
  photoTotal: number
}

export default function StagePhoto({ photos, photoTotal }: Props) {
  return (
    <div className="px-8 py-5 border-b border-hairline-dark">
      {/* 标题栏 */}
      <div className="flex items-center justify-between mb-3">
        <span className="text-base font-medium text-body-dark">剧照</span>
        <div className="flex items-center text-sm text-muted cursor-pointer hover:text-body-dark transition-colors">
          <span className="font-plex">全部 {photoTotal} 张</span>
          <ChevronRight className="w-4 h-4" />
        </div>
      </div>
      {/* 照片列表 */}
      <div className="flex gap-3 overflow-x-auto scrollbar-hide">
        {photos.map((photo, index) => (
          <img
            key={index}
            src={imgUrlReplace(photo)}
            alt={`剧照 ${index + 1}`}
            className="w-[200px] h-[130px] rounded-lg object-cover flex-shrink-0 hover:opacity-85 hover:scale-[1.02] transition-all cursor-pointer border border-hairline-dark"
          />
        ))}
      </div>
    </div>
  )
}
