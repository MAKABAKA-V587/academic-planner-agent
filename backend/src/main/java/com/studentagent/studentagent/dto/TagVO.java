package com.studentagent.studentagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TagVO {
    private String name;
    private int weight;  // 1-5
}
