BINDIR = bin
SRCDIR = src

CXX = g++
CXXFLAGS = -I/usr/X11/include -include $(SRCDIR)/config.h
LDFLAGS = -L/usr/X11/lib
LIBS = -lpng 
CXXARGS = $(CXXFLAGS) $(LDFLAGS) $(LIBS)

TWIKI_PLUGIN_MAKEFILE = twiki/JBrowsePlugin/Makefile.jbrowse

all: $(BINDIR)/wig2png

clean:
	rm $(BINDIR)/wig2png

jbrowse: all
	$(MAKE) -f $(TWIKI_PLUGIN_MAKEFILE) all

$(BINDIR)/wig2png: $(SRCDIR)/wig2png.cc
	$(CXX) $(CXXARGS) -o $@ $<

.PHONY: all clean jbrowse

.SECONDARY:
