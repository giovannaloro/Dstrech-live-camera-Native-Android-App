CXX      ?= c++
CXXFLAGS ?= -std=c++17 -O2 -Wall -Wextra

dstrech: main.o dstrech.o helpers.o
	$(CXX) $(CXXFLAGS) -o $@ $^

%.o: %.cpp dstrech.hpp
	$(CXX) $(CXXFLAGS) -c -o $@ $<

clean:
	rm -f *.o dstrech

.PHONY: clean
