private fun checkAndJumpToTargetChapter(view: WebView) {
        if (targetChapter <= 0) return
        val target = targetChapter
        targetChapter = 0 // Reset immediately so the destination page's onPageFinished doesn't loop
        val js = """
            (function() {
              var targetCh = $target;
              var curMatch = /(?:chapter|ch)[-/]?(\d+(?:\.\d+)?)/i.exec(window.location.href);
              if (curMatch && parseFloat(curMatch[1]) === parseFloat(targetCh)) return;

              var ticks = 0;
              var timer = setInterval(function() {
                ticks++;
                if (ticks > 40) { clearInterval(timer); return; }

                var nowMatch = /(?:chapter|ch)[-/]?(\d+(?:\.\d+)?)/i.exec(window.location.href);
                if (nowMatch && parseFloat(nowMatch[1]) === parseFloat(targetCh)) {
                    clearInterval(timer);
                    return;
                }

                if (ticks === 1) {
                    var triggers = document.querySelectorAll('.rpage-floatctl__chap, .chapter-list-btn, .btn-chapter-list, .toggle-chapters');
                    if (triggers.length === 0) {
                        triggers = document.querySelectorAll('[aria-label*="chapter list" i], [aria-label*="chapter menu" i], [title*="chapter list" i]');
                    }
                    if (triggers.length > 0) triggers[0].click();
                }

                var anchors = document.querySelectorAll('a[href]');
                for (var i = 0; i < anchors.length; i++) {
                    var aHref = anchors[i].href || '';
                    var hm = /(?:chapter|ch)[-/]?(\d+(?:\.\d+)?)/i.exec(aHref);
                    if (hm && parseFloat(hm[1]) === parseFloat(targetCh)) {
                        window.location.href = aHref;
                        clearInterval(timer);
                        return;
                    }
                }

                for (var k = 0; k < anchors.length; k++) {
                    var txt = (anchors[k].innerText || anchors[k].textContent || '').trim().toLowerCase();
                    if ((txt === 'chapter ' + targetCh || txt === 'ch ' + targetCh || txt === 'ch. ' + targetCh)
                        && !/(?:chapter|ch)[-/]?(\d+)/i.test(anchors[k].href || '')) {
                        window.location.href = anchors[k].href;
                        clearInterval(timer);
                        return;
                    }
                }

                var select = document.querySelector('select');
                if (select) {
                    for (var j = 0; j < select.options.length; j++) {
                        if (select.options[j].text.includes(targetCh)) {
                            select.selectedIndex = j;
                            select.dispatchEvent(new Event('change', { bubbles: true }));
                            clearInterval(timer);
                            return;
                        }
                    }
                }
              }, 100);
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }
