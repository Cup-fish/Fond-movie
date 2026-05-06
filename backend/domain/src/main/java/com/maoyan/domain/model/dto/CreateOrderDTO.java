package com.maoyan.domain.model.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 创建订单请求
 */
@Data
public class CreateOrderDTO implements Serializable {

    /** 场次ID */
    @NotNull(message = "场次ID不能为空")
    private Long scheduleId;

    private String lockToken;

    @Size(max = 6, message = "最多选择6个座位")
    private List<LockSeatsDTO.SeatPos> seats;

    /** 购票数量 */
    @NotNull(message = "购票数量不能为空")
    @Min(value = 1, message = "至少购买1张票")
    private Integer seatCount;

    /** 座位描述(如 "5排3座,5排4座") */
    private String seatsInfo;
}
