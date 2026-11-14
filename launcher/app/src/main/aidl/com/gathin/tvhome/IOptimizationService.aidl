package com.gathin.tvhome;
interface IOptimizationService {
    String inventory() = 0;
    String prepare(String packageName) = 1;
    void freeze(String packageName) = 2;
    void restore(String packageName, int originalState) = 3;
    void destroy() = 16777114;
}
