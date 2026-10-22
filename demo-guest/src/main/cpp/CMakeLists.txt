cmake_minimum_required(VERSION 3.22.1)
project(clientbase_demo LANGUAGES CXX)
add_library(clientbase_demo SHARED demo_jni.cpp)
target_compile_features(clientbase_demo PRIVATE cxx_std_17)
