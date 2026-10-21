package org.futo.inputmethod.latin.uix.utils;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class SpeechCleanupTest {
    @Test
    public void testCleanup() {
        List<String[]> cases = Arrays.asList(
                new String[]{"Let's meet at 3, no wait, 4 pm.", "Let's meet at 4 pm."},
                new String[]{"Um, I think we should, uh, go to the store, no wait, to the mall.", "I think we should go to the mall."},
                new String[]{"Send it to John, I mean Sarah.", "Send it to Sarah."},
                new String[]{"I'll take the red one, no wait, the blue one.", "I'll take the blue one."},
                new String[]{"I I think that's fine.", "I think that's fine."},
                new String[]{"I want to, I want to go home.", "I want to go home."},
                new String[]{"There is no wait time.", "There is no wait time."},
                new String[]{"That's what I mean by fun.", "That's what I mean by fun."},
                new String[]{"Do you know what I mean?", "Do you know what I mean?"},
                new String[]{"I'll be late. Scratch that. I'll be on time.", "I'll be on time."},
                new String[]{"I know that that is true.", "I know that that is true."},
                new String[]{"It was very very good.", "It was very very good."},
                new String[]{"Hmm.", ""},
                new String[]{"Buy milk, sorry I mean eggs, and bread.", "Buy eggs, and bread."},
                new String[]{"So, um, yeah.", "So yeah."},
                new String[]{"Call me tomorrow, no sorry, Friday at noon.", "Call me Friday at noon."},
                new String[]{"I need two, no wait, three apples.", "I need three apples."},
                new String[]{"Pick up the kids at 5, no wait, 6.", "Pick up the kids at 6."},
                new String[]{"Let's go with the blue one, no sorry, the green one, thanks.", "Let's go with the green one, thanks."},
                new String[]{"The meeting is on Monday, I mean, the meeting is on Tuesday.", "The meeting is on Tuesday."},
                new String[]{"I mean, it's fine.", "I mean, it's fine."},
                new String[]{"Um.", ""},
                new String[]{"Hello there, um.", "Hello there."},
                new String[]{"Ask not, uh, what your country can do.", "Ask not what your country can do."},
                new String[]{"And so, my fellow Americans, ask not what your country can do for you.", "And so, my fellow Americans, ask not what your country can do for you."}
        );

        for (String[] testCase : cases) {
            Assert.assertEquals("Failed for '" + testCase[0] + "'", testCase[1], SpeechCleanup.clean(testCase[0]));
        }
    }
}
